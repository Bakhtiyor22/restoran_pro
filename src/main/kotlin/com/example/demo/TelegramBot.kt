package com.example.demo

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.math.BigDecimal
import java.util.Locale

@Component
class TelegramBot(
    @Value("\${telegram.bot.token}") private val botToken: String,
    @Value("\${telegram.bot.name}") private val botName: String,
    private val authService: AuthService,
    private val productService: ProductService,
    private val categoryRepository: CategoryRepository,
    private val localizedMessageService: LocalizedMessageService,
    private val userRepository: UserRepository,
    private val addressRepository: AddressRepository,
    private val cartService: CartService,
    private val orderService: OrderServiceImpl,
    private val orderManager: OrderManager,
    private val addressService: AddressService,
    private val localeService: LocaleService,
    private val userStateService: UserStateServiceImpl,
    private val userStateRepository: UserStateRepository
): TelegramLongPollingBot(botToken) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val HELP_TEXT_KEY = "help.text"

    private val listOfCommands: List<BotCommand> = listOf(
        BotCommand("/start", "Start interaction"),
        BotCommand("/menu", "Show main menu"),
        BotCommand("/deletedata", "Delete your data"),
        BotCommand("/help", "Show help info"),
        BotCommand("/settings", "Change settings"),
        BotCommand("/register", "Register (part of /start flow)")
    )

    @PostConstruct
    fun init() {
        try {
            val botsApi = TelegramBotsApi(DefaultBotSession::class.java)
            botsApi.registerBot(this)
            initCommands(listOfCommands)
            logger.info("Bot $botName initialized and registered.")
        } catch (e: TelegramApiException) {
            logger.error("Error initializing or registering bot: ${e.message}", e)
        }
    }

    private fun initCommands(listOfCommands: List<BotCommand>) {
        try {
            execute(
                SetMyCommands.builder()
                    .commands(listOfCommands)
                    .build()
            )
        } catch (e: TelegramApiException) {
            logger.error("Failed to set bot commands: ${e.message}", e)
        }
    }

    override fun getBotUsername() = botName

    override fun onUpdateReceived(update: Update) {
        try {
            when {
                update.hasCallbackQuery() -> {
                    handleCallbackQuery(update.callbackQuery)
                }

                update.hasMessage() -> {
                    val message = update.message
                    val chatId = message.chatId

                    val currentState = getCurrentState(chatId)

                    if (message.isReply && currentState == BotState.AWAITING_OTP) {
                        val originalMessage = message.replyToMessage
                        val expectedPrompt = localizedMessageService.getMessage("otp.prompt", chatId)

                        if (originalMessage?.text == expectedPrompt) {
                            handleOtpReply(chatId, message.text)
                            return
                        }
                    }

                    when {
                        message.hasLocation() && currentState == BotState.AWAITING_ADDRESS -> {
                            val location = message.location
                            saveUserAddress(
                                chatId,
                                location.latitude,
                                location.longitude
                            )
                        }

                        message.hasContact() && currentState == BotState.AWAITING_PHONE -> {
                            val contact = message.contact

                            if (contact.userId != message.from.id) {
                                sendLocalizedMessage(chatId, "error.invalid_contact")
                                register(chatId, message.from.firstName)
                                return
                            }

                            val phoneNumber = contact.phoneNumber
                            sendOtp(chatId, phoneNumber)
                        }

                        message.hasText() -> {
                            handleTextMessage(chatId, message.text, currentState)
                        }

                        else -> {
                            logger.warn("Received unhandled message type from chat $chatId")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            val chatId = update.message?.chatId ?: update.callbackQuery?.message?.chatId
            if (chatId != null) {
                sendMessage(chatId, localizedMessageService.getMessage("error.generic", chatId))
            }
        }
    }

    private fun handleCallbackQuery(callbackQuery: CallbackQuery) {
        val chatId = callbackQuery.message?.chatId ?: return
        val data = callbackQuery.data ?: return
        val messageId = callbackQuery.message?.messageId ?: return

        try {
            if (data.contains(":")) {
                val parts = data.split(":")

                when {
                    data.startsWith("product:") -> {
                        val productId = parts[1].toLong()
                        handleProductSelection(chatId, productId)
                    }
                    data.startsWith("category:") -> {
                        val categoryId = parts[1].toLong()
                        showProductMenu(chatId, categoryId)
                    }
                    data.startsWith("increase_quantity:") || data.startsWith("decrease_quantity:") || data.startsWith("add_to_cart:") -> {
                        handleQuantityAdjustment(callbackQuery)
                    }
                    data.startsWith("address:") -> {
                        val addressId = parts[1].toLong()

                        processOrderWithAddress(chatId, addressId)
                    }
                    data.startsWith("select_address:") -> {
                        handleAddressSelection(callbackQuery)
                    }
                    data.startsWith("set_lang:") -> {
                        val langCode = parts[1]
                        localeService.setUserLocale(chatId, langCode)

                        val confirmationText = localizedMessageService.getMessage("language.selected", chatId)
                        execute(EditMessageText().apply {
                            setChatId(chatId.toString())
                            setMessageId(messageId)
                            text = confirmationText
                        })

                        proceedAfterLanguageSelected(chatId)
                    }
                    else -> {
                        sendMessage(chatId, "Sorry.")
                    }
                }
            } else {
                when (data) {
                    "main_menu" -> showMainMenu(chatId)
                    "view_cart" -> handleViewCart(chatId)
                    "checkout" -> handleCheckout(chatId)
                    "back_to_products" -> showCategoriesMenu(chatId)
                    "confirm_order" -> handleOrderConfirmation(callbackQuery)
                    "cancel_order" -> {
                        sendMessage(chatId, localizedMessageService.getMessage("order.cancelled", chatId))
                        showMainMenu(chatId)
                    }
                    "add_address" -> {
                        enterAddress(chatId)
                    }
                    else -> {
                        sendLocalizedMessage(chatId, "unknown.command", data)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error handling callback query: ${e.message}", e)
            sendMessage(chatId, localizedMessageService.getMessage("error.generic", chatId))
        }
    }

    private fun proceedAfterLanguageSelected(chatId: Long) {
        val existingUser = userRepository.findByTelegramChatId(chatId)

        if (existingUser != null && existingUser.phoneNumber.isNotBlank()) {
            setState(chatId, BotState.REGISTERED)
            sendLocalizedMessage(chatId, "welcome.back", existingUser.username)
            showMainMenu(chatId)
            return
        }

        val welcomeText = localizedMessageService.getMessage("welcome.message", chatId, "Customer")
        sendMessage(chatId, welcomeText)
        register(chatId)
    }

    private fun register(chatId: Long, firstName: String? = null) {
        try {
            val displayName = if (!firstName.isNullOrBlank()){
                "$firstName"
            } else {
                "Customer"
            }
            var user = userRepository.findByTelegramChatId(chatId)
            if (user == null) {
                user = User(
                    username = displayName,
                    phoneNumber = "",
                    password = "",
                    role = Roles.CUSTOMER,
                    telegramChatId = chatId
                )
                userRepository.save(user)

                val userState = UserState(user = user)
                userStateRepository.save(userState)

            }
        } catch (e: Exception) {
            logger.error("Error creating predefined user: ${e.message}", e)
        }

        val message = SendMessage()
        message.chatId = chatId.toString()
        message.text = localizedMessageService.getMessage(
            "register.prompt_phone",
            chatId
        )

        val markup = ReplyKeyboardMarkup().apply {
            keyboard = listOf(
                KeyboardRow().apply {
                    add(
                        KeyboardButton(
                            localizedMessageService.getMessage(
                                "button.register.share_phone",
                                chatId
                            )
                        ).apply {
                            requestContact = true
                        })
                }
            )
            oneTimeKeyboard = true
            resizeKeyboard = true
        }
        message.replyMarkup = markup

        try {
            execute(message)
            setState(chatId, BotState.AWAITING_PHONE)
        } catch (e: TelegramApiException) {
            logger.error("Error sending phone request message to chat $chatId: ${e.message}", e)
        }
    }
    private fun handleAddressSelection(callbackQuery: CallbackQuery) {
        val chatId = callbackQuery.message?.chatId ?: return
        val data = callbackQuery.data

        if (data.startsWith("select_address:")) {
            val addressId = data.substringAfter("select_address:").toLong()
            orderManager.setAddressId(chatId, addressId)
            sendLocalizedMessage(chatId, "address.selected")
            setState(chatId, BotState.REGISTERED)
            showMainMenu(chatId)
        } else if (data == "add_address") {
            setState(chatId, BotState.AWAITING_ADDRESS)
            enterAddress(chatId)
        }
    }

    private fun handleAddressRequest(chatId: Long) {
        val userDetails = userStateService.getUserState(chatId)

        val existingAddresses = try {
            addressService.getUserAddresses(userDetails.id!!)
        } catch (e: Exception) {
            emptyList()
        }

        if (existingAddresses.isNotEmpty()) {
            val markup = InlineKeyboardMarkup()
            val rows = existingAddresses.map { address ->
                listOf(InlineKeyboardButton().apply {
                    text = "${address.addressLine}, ${address.city}"
                    callbackData = "select_address:${address.id}"
                })
            }.toMutableList()

            rows.add(listOf(InlineKeyboardButton().apply {
                text = localizedMessageService.getMessage("button.add_new_address", chatId)
                callbackData = "add_address"
            }))

            markup.keyboard = rows
            sendMessage(chatId, SendMessage().apply {
                text = localizedMessageService.getMessage("address.select_or_add", chatId)
                replyMarkup = markup
            })
        } else {
            setState(chatId, BotState.AWAITING_ADDRESS)
            sendMessage(chatId, localizedMessageService.getMessage("button.add_new_address", chatId))
        }
    }

    private fun handleTextMessage(chatId: Long, text: String, currentState: BotState) {
        when (text) {
            "/start" -> {
                handleStartCommand(chatId)
                return
            }

            "/help" -> {
                sendLocalizedMessage(chatId, HELP_TEXT_KEY)
                return
            }

            "/settings" -> {
                if (currentState == BotState.REGISTERED) {
                    showSettingsMenu(chatId)
                } else {
                    sendLocalizedMessage(
                        chatId,
                        "error.command.unavailable",
                        text
                    )
                }
                return
            }

            "/menu" -> {
                if (currentState == BotState.REGISTERED) {
                    showMainMenu(chatId)
                } else {
                    sendLocalizedMessage(chatId, "error.command.unavailable", text)
                }
                return
            }

            "/deletedata" -> {
                if (currentState == BotState.REGISTERED) {
                    promptForDataDeletion(chatId)
                } else {
                    sendLocalizedMessage(chatId, "error.command.unavailable", text)
                }
                return
            }
        }

        if (currentState == BotState.REGISTERED || currentState.name.startsWith("MENU_")) {
            localeService.getUserLocale(chatId)
            val menuButtonText = localizedMessageService.getMessage("button.menu", chatId)
            val cartButtonText = localizedMessageService.getMessage("button.cart", chatId)
            val addressesButtonText = localizedMessageService.getMessage("button.my_addresses", chatId)
            val settingsButtonText = localizedMessageService.getMessage("button.settings", chatId)
            val returnButtonText = localizedMessageService.getMessage("button.return", chatId)

            when (text) {
                menuButtonText -> {
                    showCategoriesMenu(chatId)
                }

                cartButtonText -> {
                    showCart(chatId)
                }

                addressesButtonText -> {
                    showAddresses(chatId)
                }

                settingsButtonText -> {
                    showSettingsMenu(chatId)
                }

                returnButtonText -> {
                    handleReturnAction(chatId)
                }

                else -> {
                    val currentMenuState = getCurrentMenuState(chatId)

                    if (currentMenuState == MenuStates.CATEGORY_VIEW) {
                        val categoryId = findCategoryIdByName(chatId, text)
                        if (categoryId != null) {
                            showProductMenu(chatId, categoryId)
                        } else {
                            sendLocalizedMessage(chatId, "unknown.command", text)
                        }
                    } else if (currentMenuState == MenuStates.PRODUCT_VIEW) {
                        val productId = findProductIdByName(chatId, text)
                        if (productId != null) {
                            showProductDetailsOrAddToCart(chatId, productId)
                        } else {
                            sendLocalizedMessage(chatId, "unknown.command", text)
                        }
                    } else {
                        sendLocalizedMessage(chatId, "unknown.command", text)
                    }
                }
            }
        } else {
            when (currentState) {
                BotState.AWAITING_LANGUAGE -> sendLocalizedMessage(chatId, "error.select_language_first")
                BotState.AWAITING_PHONE -> sendLocalizedMessage(chatId, "error.share_contact_first")
                BotState.AWAITING_OTP -> sendLocalizedMessage(chatId, "error.enter_otp_first")
                BotState.AWAITING_ADDRESS -> sendLocalizedMessage(chatId, "error.share_location_first")
                BotState.PRODUCT_DETAIL_VIEW -> sendLocalizedMessage(chatId, "error.select_product_first")
                else -> sendLocalizedMessage(chatId, "unknown.command", text)
            }
        }
    }

    private fun handleStartCommand(chatId: Long) {
        try {
            val existingUser = userRepository.findByTelegramChatId(chatId)

            if (existingUser != null && existingUser.phoneNumber.isNotBlank()) {
                val userState = userStateRepository.findByUserId(existingUser.id!!)
                val phoneVerified = userState?.temporaryData?.let {
                    val dataMap = ObjectMapper().readValue(it, object : TypeReference<Map<String, String>>() {})
                    dataMap["phone_verified"] == "true"
                } ?: false

                if (phoneVerified) {
                    setState(chatId, BotState.REGISTERED)
                    sendLocalizedMessage(chatId, "welcome.back", existingUser.username)
                    showMainMenu(chatId)
                    return
                }
            }

            setState(chatId, BotState.START)
            promptForLanguageSelection(chatId)
        } catch (e: Exception) {
            logger.error("Error in handleStartCommand: ${e.message}", e)
            setState(chatId, BotState.START)
            promptForLanguageSelection(chatId)
        }
    }

    private fun promptForLanguageSelection(chatId: Long) {
        val message = SendMessage()
        message.chatId = chatId.toString()
        val promptText = localizedMessageService.getMessage("language.select", Locale.forLanguageTag("uz"))

        message.text = promptText

        val inlineKeyboardMarkup = InlineKeyboardMarkup().apply {
            keyboard = listOf(
                listOf(
                    InlineKeyboardButton().apply { text = "🇺🇿 O'zbekcha"; callbackData = "set_lang:uz" },
                    InlineKeyboardButton().apply { text = "🇷🇺 Русский"; callbackData = "set_lang:ru" }
                )
            )
        }
        message.replyMarkup = inlineKeyboardMarkup

        try {
            execute(message)
            setState(chatId, BotState.AWAITING_LANGUAGE)
        } catch (e: TelegramApiException) {
            logger.error("Error sending language selection message to chat $chatId: ${e.message}", e)
        }
    }

    private fun sendOtp(chatId: Long, phoneNumber: String) {
        try {
            if (!validatePhoneNumber(phoneNumber)) {
                sendLocalizedMessage(chatId, "error.invalid_phone_number")
                register(chatId)
                return
            }

            val response = authService.requestOtp(OtpRequest(phoneNumber), chatId)
            userStateService.setTemporaryData(chatId, "phone_number", phoneNumber)
            userStateService.setTemporaryData(chatId, "otp_id", response.smsCodeId.toString())

            val message = SendMessage()
            message.chatId = chatId.toString()
            message.text = localizedMessageService.getMessage("otp.prompt", chatId)
            message.replyMarkup = ForceReplyKeyboard().apply {
                forceReply = true
                selective = true
            }

            execute(message)
            setState(chatId, BotState.AWAITING_OTP)
        } catch (e: Exception) {
            logger.error("Error requesting OTP: ${e.message}", e)
            sendLocalizedMessage(chatId, "error.otp.request_failed")
            register(chatId)
        }
    }

    private fun handleOtpReply(chatId: Long, otp: String) {
        val phoneNumber = userStateService.getTemporaryData(chatId, "phone_number")
        if (phoneNumber == null) {
            sendLocalizedMessage(chatId, "error.generic")
            setState(chatId, BotState.START)
            handleStartCommand(chatId)
            return
        }

        val otpIdString = userStateService.getTemporaryData(chatId, "otp_id")
        if (otpIdString == null) {
            sendLocalizedMessage(chatId, "error.otp.expired_or_missing")
            setState(chatId, BotState.START)
            handleStartCommand(chatId)
            return
        }

        val otpId = otpIdString.toLongOrNull()
        if (otpId == null) {
            sendLocalizedMessage(chatId, "error.generic")
            setState(chatId, BotState.START)
            handleStartCommand(chatId)
            return
        }

        try {
            authService.otpLogin(OtpLogin(phoneNumber, otp, otpId), chatId)

            val user = userRepository.findByTelegramChatId(chatId)
            if (user != null) {
                user.phoneNumber = phoneNumber
                userRepository.save(user)

                userStateService.setTemporaryData(chatId, "phone_verified", "true")
            }

            userStateService.clearTemporaryData(chatId)
            enterAddress(chatId)
        } catch (e: Exception) {
            logger.error("OTP validation failed: ${e.message}", e)
            sendLocalizedMessage(chatId, "error.otp.invalid")
            register(chatId)
        }
    }

    private fun enterAddress(chatId: Long) {
        val message = SendMessage()
        message.chatId = chatId.toString()
        message.text =
            localizedMessageService.getMessage("address.prompt", chatId)

        val markup = ReplyKeyboardMarkup().apply {
            keyboard = listOf(
                KeyboardRow().apply {
                    add(KeyboardButton(localizedMessageService.getMessage("button.share_location", chatId)).apply {
                        requestLocation = true
                    })
                }
            )
            resizeKeyboard = true
            oneTimeKeyboard = true
        }
        message.replyMarkup = markup

        try {
            execute(message)
            setState(chatId, BotState.AWAITING_ADDRESS)
        } catch (e: TelegramApiException) {
            logger.error("Error send to address $chatId: ${e.message}", e)
        }
    }

    private fun saveUserAddress(chatId: Long, latitude: Double, longitude: Double) {
        val user = userRepository.findByTelegramChatId(chatId)
        if (user == null) {
            sendLocalizedMessage(chatId, "error.auth.required")
            setState(chatId, BotState.START)
            handleStartCommand(chatId)
            return
        }

        try {
            val addressRequest = AddressRequest(
                addressLine = "Lat: $latitude, Lon: $longitude",
                city = "Tashkent",
                latitude = latitude.toFloat(),
                longitude = longitude.toFloat()
            )

            val address = Address(
                addressRequest.addressLine,
                addressRequest.city,
                addressRequest.latitude,
                addressRequest.longitude,
                user
            )

            addressRepository.save(address)

            sendLocalizedMessage(chatId, "address.saved")
            setState(chatId, BotState.REGISTERED)
            showMainMenu(chatId)
        } catch (e: Exception) {
            logger.error("Failed to save address: ${e.message}", e)
            sendLocalizedMessage(chatId, "error.address.save_failed")
            enterAddress(chatId)
        }
    }

    private fun showMainMenu(chatId: Long) {
        setState(chatId, BotState.REGISTERED)

        val message = SendMessage()
        message.chatId = chatId.toString()
        message.text = localizedMessageService.getMessage("main_menu.prompt", chatId)

        val markup = ReplyKeyboardMarkup().apply {
            keyboard = listOf(
                KeyboardRow().apply {
                    add(KeyboardButton(localizedMessageService.getMessage("button.menu", chatId)))
                    add(KeyboardButton(localizedMessageService.getMessage("button.cart", chatId)))
                },
                KeyboardRow().apply {
                    add(KeyboardButton(localizedMessageService.getMessage("button.my_addresses", chatId)))
                    add(KeyboardButton(localizedMessageService.getMessage("button.settings", chatId)))
                }
            )
            resizeKeyboard = true
        }
        message.replyMarkup = markup

        sendMessage(chatId, message)
    }

    private fun showCategoriesMenu(chatId: Long) {
        setPreviousState(chatId, getCurrentState(chatId))
        setMenuState(chatId, MenuStates.CATEGORY_VIEW)

        val allCategories = categoryRepository.findAllNotDeleted()
        val replyKeyboardMarkup = ReplyKeyboardMarkup()
        val keyboard = ArrayList<KeyboardRow>()
        val locale = getUserLocale(chatId)

        var currentRow = KeyboardRow()

        allCategories.forEachIndexed { index, category ->
            val buttonText = when (locale) {
                "uz" -> category.nameUz
                "ru" -> category.nameRu
                else -> category.name
            }


            if (buttonText.isNotBlank()) {
                currentRow.add(KeyboardButton(buttonText))
            }

            if ((index + 1) % 2 == 0 || index == allCategories.size - 1) {
                keyboard.add(currentRow)
                currentRow = KeyboardRow()
            }
        }

        val backRow = KeyboardRow()
        backRow.add(KeyboardButton(localizedMessageService.getMessage("button.return", chatId)))
        keyboard.add(backRow)

        replyKeyboardMarkup.keyboard = keyboard
        replyKeyboardMarkup.resizeKeyboard = true

        val message = SendMessage()
        message.chatId = chatId.toString()
        message.text = localizedMessageService.getMessage("category.choose", chatId)
        message.replyMarkup = replyKeyboardMarkup

        execute(message)
    }

    private fun handleProductSelection(chatId: Long, productId: Long) {
        val product = productService.getProductById(productId) ?: run {
            sendMessage(chatId, localizedMessageService.getMessage("error.product_not_found", chatId))
            return
        }

        showProductQuantitySelector(chatId, productId, product)
    }

    private fun showProductQuantitySelector(chatId: Long, productId: Long, product: ProductDTO) {
        val markup = InlineKeyboardMarkup()
        markup.keyboard = listOf(
            listOf(
                InlineKeyboardButton().apply {
                    text = "➖"
                    callbackData = "decrease_quantity:$productId:1"
                },
                InlineKeyboardButton().apply {
                    text = "1"
                    callbackData = "quantity_info"
                },
                InlineKeyboardButton().apply {
                    text = "➕"
                    callbackData = "increase_quantity:$productId:1"
                }
            ),
            listOf(
                InlineKeyboardButton().apply {
                    text = "Add to Cart"
                    callbackData = "add_to_cart:$productId:1"
                }
            ),
            listOf(
                InlineKeyboardButton().apply {
                    text = "Back"
                    callbackData = "back_to_products"
                }
            )
        )

        sendMessage(
            chatId,
            SendMessage().apply {
                text = "Product: ${product.name}\nPrice: ${product.price} ${product.currency}\nQuantity: 1"
                replyMarkup = markup
            }
        )
    }

    private fun handleQuantityAdjustment(callbackQuery: CallbackQuery) {
        val chatId = callbackQuery.message?.chatId ?: return
        val data = callbackQuery.data ?: return
        val messageId = callbackQuery.message?.messageId ?: return

        val parts = data.split(":")
        if (parts.size < 3) return

        val action = parts[0]
        val productId = parts[1].toLong()
        var currentQuantity = parts[2].toInt()

        when (action) {
            "increase_quantity" -> {
                currentQuantity++
            }
            "decrease_quantity" -> {
                if (currentQuantity > 1) {
                    currentQuantity--
                }
            }
            "add_to_cart" -> {
                cartService.addItemToCart(chatId, productId, currentQuantity)
                sendLocalizedMessage(chatId, "cart.added_items", currentQuantity)
                handleViewCart(chatId)
                return
            }
        }

        val product = productService.getProductById(productId) ?: return

        val messageText = "Product: ${product.name}\nPrice: ${product.price} ${product.currency}\nQuantity: $currentQuantity"

        val markup = InlineKeyboardMarkup()
        markup.keyboard = listOf(
            listOf(
                InlineKeyboardButton().apply {
                    text = "➖"
                    callbackData = "decrease_quantity:$productId:$currentQuantity"
                },
                InlineKeyboardButton().apply {
                    text = currentQuantity.toString()
                    callbackData = "quantity_info"
                },
                InlineKeyboardButton().apply {
                    text = "➕"
                    callbackData = "increase_quantity:$productId:$currentQuantity"
                }
            ),
            listOf(
                InlineKeyboardButton().apply {
                    text = localizedMessageService.getMessage("button.add_to_cart", chatId)
                    callbackData = "add_to_cart:$productId:$currentQuantity"
                }
            ),
            listOf(
                InlineKeyboardButton().apply {
                    text = localizedMessageService.getMessage("button.return", chatId)
                    callbackData = "back_to_products"
                }
            )
        )

        try {
            execute(EditMessageText().apply {
                setChatId(chatId)
                setMessageId(messageId)
                text = messageText
                replyMarkup = markup
            })
        } catch (e: TelegramApiException) {
            logger.error("Error updating message: ${e.message}", e)
        }
    }

    private fun handleViewCart(chatId: Long) {
        try {
            val cart = cartService.getCart(chatId)

            if (cart.items.isEmpty()) {
                sendLocalizedMessage(chatId, "cart.empty")
                return
            }

            val messageBuilder = StringBuilder(localizedMessageService.getMessage("cart.title", chatId))
            messageBuilder.append("\n\n")

            var total = 0.0
            cart.items.forEach { item ->
                val productName = getLocalizedProductName(item.product, chatId)
                val itemTotal = item.quantity.toDouble().times(item.product.price.toDouble())
                total += itemTotal

                messageBuilder.append("$productName x ${item.quantity} = ${itemTotal}\n")
            }

            messageBuilder.append("\n${localizedMessageService.getMessage("cart.total", chatId)}: $total")

            val message = SendMessage()
            message.chatId = chatId.toString()
            message.text = messageBuilder.toString()

            val markup = InlineKeyboardMarkup()
            val keyboardRows = mutableListOf<List<InlineKeyboardButton>>()

            val checkoutButton = InlineKeyboardButton(localizedMessageService.getMessage("button.checkout", chatId))
            checkoutButton.callbackData = "checkout"

            val continueButton =
                InlineKeyboardButton(localizedMessageService.getMessage("button.continue_shopping", chatId))
            continueButton.callbackData = "main_menu"

            keyboardRows.add(listOf(checkoutButton))
            keyboardRows.add(listOf(continueButton))

            markup.keyboard = keyboardRows
            message.replyMarkup = markup

            sendMessage(chatId, message)
        } catch (e: Exception) {
            sendLocalizedMessage(chatId, "error.cart.view_failed")
        }
    }

    private fun handleCheckout(chatId: Long) {
        try {
            val cart = cartService.getCart(chatId)

            if (cart.items.isEmpty()) {
                sendLocalizedMessage(chatId, "cart.empty")
                return
            }

            val user = userRepository.findByTelegramChatId(chatId)
            if (user == null) {
                sendLocalizedMessage(chatId, "error.auth.required")
                setState(chatId, BotState.START)
                handleStartCommand(chatId)
                return
            }

            val addresses = addressRepository.findAllByUserIdAndDeletedFalse(user.id!!)

            if (addresses.isEmpty()) {
                sendLocalizedMessage(chatId, "checkout.no_addresses")
                enterAddress(chatId)
                return
            }

            val message = SendMessage()
            message.chatId = chatId.toString()
            message.text = localizedMessageService.getMessage("checkout.select_address", chatId)

            val markup = InlineKeyboardMarkup()
            val keyboardRows = mutableListOf<List<InlineKeyboardButton>>()

            addresses.forEach { address ->
                val addressButton = InlineKeyboardButton().apply {
                    text = "${address.addressLine} (${address.city})"
                    callbackData = "address:${address.id}"
                }
                keyboardRows.add(listOf(addressButton))
            }

            val addNewAddressButton = InlineKeyboardButton().apply {
                text = localizedMessageService.getMessage("button.add_new_address", chatId)
                callbackData = "add_address"
            }
            keyboardRows.add(listOf(addNewAddressButton))

            markup.keyboard = keyboardRows
            message.replyMarkup = markup

            sendMessage(chatId, message)
            setState(chatId, BotState.AWAITING_ADDRESS_SELECTION)
        } catch (e: Exception) {
            logger.error("Error during checkout: ${e.message}", e)
            sendLocalizedMessage(chatId, "error.checkout")
        }
    }

    private fun proceedToOrderConfirmation(chatId: Long) {
        val cart = cartService.getCart(chatId)
        if (cart.items.isEmpty()) {
            sendMessage(chatId, localizedMessageService.getMessage("cart.empty", chatId))
            return
        }

        showOrderConfirmation(chatId, cart)
    }

    private fun showProductMenu(chatId: Long, categoryId: Long) {
        setPreviousState(chatId, getCurrentState(chatId))
        setMenuState(chatId, MenuStates.PRODUCT_VIEW)

        userStateService.setTemporaryData(chatId, "current_category_id", categoryId.toString())

        val message = SendMessage()
        message.chatId = chatId.toString()

        val markup = InlineKeyboardMarkup()
        val keyboardRows = mutableListOf<List<InlineKeyboardButton>>()
        val products = productService.getProductsByCategoryId(categoryId)

        if (products.isEmpty()) {
            message.text = localizedMessageService.getMessage("product.none_in_category", chatId)
        } else {
            message.text = localizedMessageService.getMessage("product.choose", chatId)

            var currentRow = mutableListOf<InlineKeyboardButton>()
            products.forEachIndexed { index, product ->
                val localizedProductName = getLocalizedProductName(product.toDto(), chatId)

                val productButton = InlineKeyboardButton(localizedProductName)
                productButton.callbackData = "product:${product.id}"


                currentRow.add(productButton)

                if (index % 2 == 1 || index == products.size - 1) {
                    keyboardRows.add(currentRow)
                    if (index != products.size - 1) {
                        currentRow = mutableListOf()
                    }
                }
            }
        }

        val returnButton = InlineKeyboardButton(localizedMessageService.getMessage("button.return", chatId))
        returnButton.callbackData = "main_menu"
        keyboardRows.add(listOf(returnButton))

        markup.keyboard = keyboardRows
        message.replyMarkup = markup
        sendMessage(chatId, message)
    }

    private fun handleReturnAction(chatId: Long) {
        val menuState = getCurrentMenuState(chatId)

        when (menuState) {
            MenuStates.PRODUCT_VIEW -> {
                setMenuState(chatId, MenuStates.CATEGORY_VIEW)
                showCategoriesMenu(chatId)
            }

            MenuStates.CATEGORY_VIEW -> {
                setMenuState(chatId, MenuStates.MAIN_MENU)
                showMainMenu(chatId)
            }

            else -> {
                showMainMenu(chatId)
            }
        }

        setPreviousState(chatId, BotState.REGISTERED)
    }

    private fun sendMessage(chatId: Long, text: String) {
        try {
            execute(SendMessage(chatId.toString(), text))
        } catch (e: TelegramApiException) {
            logger.error("Failed to send message to chat $chatId: ${e.message}", e)
        }
    }

    private fun sendMessage(chatId: Long, message: SendMessage) {
        message.chatId = chatId.toString()
        try {
            execute(message)
        } catch (e: TelegramApiException) {
             sendMessage(chatId, localizedMessageService.getMessage("error.send_failed", chatId))
        }
    }


    private fun sendLocalizedMessage(chatId: Long, key: String, vararg args: Any) {
        val localizedText = localizedMessageService.getMessage(key, chatId, *args)
        sendMessage(chatId, localizedText)
    }


    private fun getLocalizedProductName(product: ProductDTO, chatId: Long): String {
        val locale = localeService.getUserLocale(chatId)
        return when (locale.language) {
            "uz" -> product.nameUz!!
            "ru" -> product.nameRu!!
            else -> product.name
        }
    }


    private fun showSettingsMenu(chatId: Long) {
        val markup = InlineKeyboardMarkup()
        val keyboardRows = mutableListOf<List<InlineKeyboardButton>>()

        keyboardRows.add(listOf(
            InlineKeyboardButton().apply {
                text = "🇺🇿 O'zbekcha"
                callbackData = "set_lang:uz"
            },
            InlineKeyboardButton().apply {
                text = "🇷🇺 Русский"
                callbackData = "set_lang:ru"
            }
        ))

        keyboardRows.add(listOf(
            InlineKeyboardButton().apply {
                text = localizedMessageService.getMessage("button.return", chatId)
                callbackData = "main_menu"
            }
        ))

        markup.keyboard = keyboardRows

        sendMessage(chatId, SendMessage().apply {
            text = localizedMessageService.getMessage("language.select", chatId)
            replyMarkup = markup
        })
    }

    private fun showCart(chatId: Long) {
        handleViewCart(chatId)
    }

    private fun showAddresses(chatId: Long) {
        val user = getCurrentUser(chatId) ?: run {
            sendLocalizedMessage(chatId, "error.auth.required")
            return
        }

        val addresses = addressRepository.findAllByUserIdAndDeletedFalse(user.id!!)

        if (addresses.isEmpty()) {
            sendLocalizedMessage(chatId, "address.none")

            val message = SendMessage()
            message.chatId = chatId.toString()

            val markup = InlineKeyboardMarkup()
            markup.keyboard = listOf(
                listOf(
                    InlineKeyboardButton().apply {
                        text = localizedMessageService.getMessage("button.add_new_address", chatId)
                        callbackData = "add_address"
                    }
                ),
                listOf(
                    InlineKeyboardButton().apply {
                        text = localizedMessageService.getMessage("button.main_menu", chatId)
                        callbackData = "main_menu"
                    }
                )
            )

            message.replyMarkup = markup
            sendMessage(chatId, message)
            return
        }

        val messageBuilder = StringBuilder(localizedMessageService.getMessage("address.list_title", chatId))
        messageBuilder.append("\n\n")

        val markup = InlineKeyboardMarkup()
        val keyboardRows = mutableListOf<List<InlineKeyboardButton>>()

        addresses.forEachIndexed { index, address ->
            messageBuilder.append("${index + 1}. ${address.addressLine}, ${address.city}\n")

            keyboardRows.add(listOf(
                InlineKeyboardButton().apply {
                    text = "${index + 1}. ${address.addressLine}"
                    callbackData = "select_address:${address.id}"
                }
            ))
        }

        keyboardRows.add(listOf(
            InlineKeyboardButton().apply {
                text = localizedMessageService.getMessage("button.add_new_address", chatId)
                callbackData = "add_address"
            }
        ))

        keyboardRows.add(listOf(
            InlineKeyboardButton().apply {
                text = localizedMessageService.getMessage("button.main_menu", chatId)
                callbackData = "main_menu"
            }
        ))

        markup.keyboard = keyboardRows

        sendMessage(chatId, SendMessage().apply {
            text = messageBuilder.toString()
            replyMarkup = markup
        })
    }

    private fun promptForDataDeletion(chatId: Long) {
        val markup = InlineKeyboardMarkup()
        markup.keyboard = listOf(
            listOf(
                InlineKeyboardButton().apply {
                    text = "✅ Yes, delete my data"
                    callbackData = "confirm_delete_data"
                }
            ),
            listOf(
                InlineKeyboardButton().apply {
                    text = "❌ No, keep my data"
                    callbackData = "main_menu"
                }
            )
        )

        sendMessage(chatId, SendMessage().apply {
            text = "Are you sure you want to delete all your data? This action cannot be undone."
            replyMarkup = markup
        })
    }

    private fun showProductDetailsOrAddToCart(chatId: Long, productId: Long) {
        val product = productService.getProductById(productId) ?: run {
            sendLocalizedMessage(chatId, "error.product_not_found")
            return
        }

        val localizedName = getLocalizedProductName(product, chatId)
        val messageBuilder = StringBuilder("*$localizedName*\n\n")

        messageBuilder.append("${product.description}\n\n")
        messageBuilder.append("*${localizedMessageService.getMessage("price", chatId)}: ${product.price} UZS*")

        val markup = InlineKeyboardMarkup()
        markup.keyboard = listOf(
            listOf(
                InlineKeyboardButton().apply {
                    text = localizedMessageService.getMessage("button.add_to_cart", chatId)
                    callbackData = "add_to_cart:${product.id}:1"
                }
            ),
            listOf(
                InlineKeyboardButton().apply {
                    text = localizedMessageService.getMessage("button.return", chatId)
                    callbackData = "back_to_products"
                }
            )
        )

        sendMessage(chatId, SendMessage().apply {
            text = messageBuilder.toString()
            enableMarkdown(true)
            replyMarkup = markup
        })
    }

    private fun processOrderWithAddress(chatId: Long, addressId: Long) {
        orderManager.setAddressId(chatId, addressId)
        proceedToOrderConfirmation(chatId)
    }

    private fun showOrderConfirmation(chatId: Long, cart: CartDTO) {
        val messageBuilder = StringBuilder("📋 *Order Summary*\n\n")

        var subtotal = BigDecimal.ZERO
        cart.items.forEach { item ->
            val productName = getLocalizedProductName(item.product, chatId)
            val itemTotal = item.product.price.multiply(BigDecimal(item.quantity))
            subtotal = subtotal.add(itemTotal)

            messageBuilder.append("$productName x ${item.quantity} = $itemTotal ${item.product.currency}\n")
        }

        val serviceCharge = subtotal.multiply(BigDecimal("0.05"))
        val deliveryFee = BigDecimal("10000")
        val discount = BigDecimal("1000")
        val total = subtotal.add(serviceCharge).add(deliveryFee).subtract(discount)

        messageBuilder.append("\n*Subtotal:* $subtotal UZS")
        messageBuilder.append("\n*Service Fee (5%):* $serviceCharge UZS")
        messageBuilder.append("\n*Delivery Fee:* $deliveryFee UZS")
        messageBuilder.append("\n*Discount:* -$discount UZS")
        messageBuilder.append("\n\n*Total:* $total UZS")
        messageBuilder.append("\n\nPayment: Cash on Delivery")

        val markup = InlineKeyboardMarkup()
        markup.keyboard = listOf(
            listOf(
                InlineKeyboardButton().apply {
                    text = "✅ " + localizedMessageService.getMessage("button.confirm_order", chatId)
                    callbackData = "confirm_order"
                },
                InlineKeyboardButton().apply {
                    text = "❌ " + localizedMessageService.getMessage("button.cancel", chatId)
                    callbackData = "cancel_order"
                }
            )
        )

        sendMessage(chatId, SendMessage().apply {
            text = messageBuilder.toString()
            enableMarkdown(true)
            replyMarkup = markup
        })
    }

    private fun handleOrderConfirmation(callbackQuery: CallbackQuery) {
        val chatId = callbackQuery.message?.chatId ?: return

        try {
            val cart = cartService.getCart(chatId)
            if (cart.items.isEmpty()) {
                sendMessage(chatId, localizedMessageService.getMessage("cart.empty", chatId))
                return
            }

            val user = userRepository.findByTelegramChatId(chatId)
            if (user == null) {
                sendLocalizedMessage(chatId, "error.auth.required")
                setState(chatId, BotState.START)
                handleStartCommand(chatId)
                return
            }

            val addressId = orderManager.getAddressId(chatId) ?: run {
                sendMessage(chatId, localizedMessageService.getMessage("error.address.required", chatId))
                handleAddressRequest(chatId)
                return
            }

            val orderItems = cart.items.map { cartItem ->
                OrderItemRequest(
                    productId = cartItem.product.id,
                    quantity = cartItem.quantity,
                    price = cartItem.product.price
                )
            }

            val orderRequest = CreateOrderRequest(
                restaurantId = 1,
                addressId = addressId,
                items = orderItems,
                paymentOption = PaymentOption.CASH
            )

            try {
                val orderDto = orderService.createOrder(user.id!!, orderRequest)

                cartService.clearCart(chatId)

                val message = StringBuilder(localizedMessageService.getMessage("order.confirmed", chatId))
                message.append("\n")
                message.append(localizedMessageService.getMessage("order.id", chatId, orderDto.id!!))
                message.append("\n")
                message.append(localizedMessageService.getMessage("order.total", chatId, orderDto.totalAmount))

                sendMessage(chatId, message.toString())

                showMainMenu(chatId)
            } catch (e: Exception) {
                logger.error("Failed to create order: ${e.message}", e)
                sendMessage(chatId, localizedMessageService.getMessage("order.failed", chatId))
            }
        } catch (e: Exception) {
            logger.error("Error confirming order: ${e.message}", e)
            sendMessage(chatId, localizedMessageService.getMessage("error.generic", chatId))
        }
    }

    private fun findCategoryIdByName(chatId: Long, name: String): Long? {
        val locale = getUserLocale(chatId)
        val allCategories = categoryRepository.findAllNotDeleted()

        val category = allCategories.find {
            when (locale) {
                "uz" -> it.nameUz == name
                "ru" -> it.nameRu == name
                else -> it.name == name
            }
        }

        return category?.id
    }

    private fun findProductIdByName(chatId: Long, name: String): Long? {
        val locale = getUserLocale(chatId)

        val currentMenuState = getCurrentMenuState(chatId)
        if (currentMenuState == MenuStates.PRODUCT_VIEW) {
            val userState = userStateService.getUserState(chatId)
            val tempData = userState.temporaryData
            if (tempData != null) {
                val objectMapper = ObjectMapper()
                val dataMap = objectMapper.readValue(tempData, object : TypeReference<Map<String, String>>() {})
                val categoryId = dataMap["current_category_id"]?.toLongOrNull()

                if (categoryId != null) {
                    val products = productService.getProductsByCategoryId(categoryId)
                    val product = products.find {
                        when (locale) {
                            "uz" -> it.nameUz == name
                            "ru" -> it.nameRu == name
                            else -> it.name == name
                        }
                    }
                    return product?.id
                }
            }
        }

        return null
    }

    private fun getUserLocale(chatId: Long): String {
        return localeService.getUserLocale(chatId).language
    }

    private fun getCurrentUserId(chatId: Long): Long? {
        val user = userRepository.findByTelegramChatId(chatId)
        return user?.id
    }

    private fun getCurrentUser(chatId: Long): User? {
        return userRepository.findByTelegramChatId(chatId)
    }

    private fun getUserId(chatId: Long): Long {
        return userRepository.findByTelegramChatId(chatId)?.id
            ?: throw ResourceNotFoundException("User not found for chat ID: $chatId")
    }

    private fun getCurrentState(chatId: Long): BotState {
        return userStateService.getCurrentState(chatId)
    }

    private fun setState(chatId: Long, state: BotState) {
        userStateService.setState(chatId, state)
    }

    private fun getPreviousState(chatId: Long): BotState {
        return userStateService.getPreviousState(chatId)
    }

    private fun setPreviousState(chatId: Long, state: BotState) {
        userStateService.setPreviousState(chatId, state)
    }

    private fun getCurrentMenuState(chatId: Long): MenuStates {
        return userStateService.getMenuState(chatId)
    }

    private fun setMenuState(chatId: Long, state: MenuStates) {
        userStateService.setMenuState(chatId, state)
    }
}

@Configuration
class TelegramConfig {
    @Bean
    fun telegramBotsApi(): TelegramBotsApi = TelegramBotsApi(DefaultBotSession::class.java)
}

@Component
class OrderManager(
    private val orderDataRepository: OrderDataRepository,
    private val userRepository: UserRepository,
    private val addressRepository: AddressRepository
) {
    fun setAddressId(chatId: Long, addressId: Long) {
        val user = userRepository.findByTelegramChatId(chatId)
            ?: throw ResourceNotFoundException("User not found")

        var orderData = orderDataRepository.findByUserIdAndDeletedFalse(user.id!!)
        if (orderData == null) {
            val address = addressRepository.findById(addressId).orElse(null)
            orderData = OrderData(user = user, address = address)
        } else {
            orderData.address = addressRepository.findById(addressId).orElse(null)
        }

        orderDataRepository.save(orderData)
    }

    fun getAddressId(chatId: Long): Long? {
        val user = userRepository.findByTelegramChatId(chatId)
            ?: throw ResourceNotFoundException(chatId)

        val orderData = orderDataRepository.findByUserIdAndDeletedFalse(user.id!!)
        return orderData?.address?.id
    }

}