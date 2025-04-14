package com.example.demo

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
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup
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
    private val stateManager: InMemoryStateManager
): TelegramLongPollingBot(botToken) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val HELP_TEXT_KEY = MessageKey.HELP_TEXT

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
            val chatId = when {
                update.hasCallbackQuery() -> update.callbackQuery.message?.chatId
                update.hasMessage() -> update.message.chatId
                else -> null
            } ?: return

            val user = userRepository.findByTelegramChatId(chatId)
            if (user != null) {
                stateManager.cacheUser(user)
            }

            when {
                update.hasCallbackQuery() -> {
                    handleCallbackQuery(update.callbackQuery)
                }

                update.hasMessage() -> {
                    val message = update.message
                    val currentState = getCurrentState(chatId)

                    if (message.isReply && currentState == BotState.AWAITING_OTP) {
                        val originalMessage = message.replyToMessage
                        val expectedPrompt = localizedMessageService.getMessage(MessageKey.OTP_PROMPT, chatId)

                        if (originalMessage?.text == expectedPrompt) {
                            handleOtpReply(chatId, message.text)
                            return
                        }
                    }

                    when {
                        message.hasLocation() && currentState == BotState.AWAITING_ADDRESS -> {
                            val location = message.location

                            sendMessage(chatId, localizedMessageService.getMessage(
                                MessageKey.LOCATION_RECEIVED, chatId,
                                location.latitude.toString(), location.longitude.toString()
                            ))

                            setTemporaryData(chatId, "temp_latitude", location.latitude.toString())
                            setTemporaryData(chatId, "temp_longitude", location.longitude.toString())

                            setState(chatId, BotState.AWAITING_ADDRESS_DETAILS)
                            sendMessage(chatId, localizedMessageService.getMessage(
                                MessageKey.ADDRESS_PROVIDE_DETAILS, chatId
                            ))
                        }

                        message.hasText() && currentState == BotState.AWAITING_ADDRESS_DETAILS -> {
                            val addressDetails = message.text
                            val latitude = getTemporaryData(chatId, "temp_latitude")?.toDoubleOrNull()
                            val longitude = getTemporaryData(chatId, "temp_longitude")?.toDoubleOrNull()

                            if (latitude != null && longitude != null) {
                                saveUserAddress(chatId, latitude, longitude, addressDetails)
                            } else {
                                sendLocalizedMessage(MessageKey.ERROR_GENERIC, chatId)
                                enterAddress(chatId)
                            }
                        }

                        message.hasContact() && currentState == BotState.AWAITING_PHONE -> {
                            val contact = message.contact

                            if (contact.userId != message.from.id) {
                                sendLocalizedMessage(MessageKey.ERROR_INVALID_CONTACT, chatId)
                                register(chatId, message.from.firstName)
                                return
                            }

                            sendMessage(chatId, localizedMessageService.getMessage(
                                MessageKey.PHONE_RECEIVED, chatId, contact.phoneNumber
                            ))

                            val phoneNumber = contact.phoneNumber
                            sendOtp(chatId, phoneNumber)
                        }

                        message.hasText() && currentState == BotState.AWAITING_OTP -> {
                            handleOtpReply(chatId, message.text)
                            return
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
                sendMessage(chatId, localizedMessageService.getMessage(MessageKey.ERROR_GENERIC, chatId))
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
                        editMessageReplyMarkup(chatId, messageId, null)
                        processOrderWithAddress(chatId, addressId)
                    }
                    data.startsWith("select_address:") -> {
                        editMessageReplyMarkup(chatId, messageId, null)
                        handleAddressSelection(callbackQuery)
                    }
                    data.startsWith("set_lang:") -> {
                        val langCode = parts[1]
                        stateManager.setUserLocale(chatId, langCode)

                        val confirmationText = localizedMessageService.getMessage(MessageKey.LANGUAGE_SELECTED, chatId)
                        execute(EditMessageText().apply {
                            setChatId(chatId.toString())
                            setMessageId(messageId)
                            text = confirmationText
                            replyMarkup = null
                        })

                        proceedAfterLanguageSelected(chatId)
                    }
                    else -> {
                        sendMessage(chatId, "Sorry.")
                    }
                }
            } else {
                when (data) {
                    "main_menu" -> {
                        editMessageReplyMarkup(chatId, messageId, null)
                        showMainMenu(chatId)
                    }
                    "view_cart" -> handleViewCart(chatId)
                    "checkout" -> {
                        editMessageReplyMarkup(chatId, messageId, null)
                        handleCheckout(chatId)
                    }
                    "back_to_products" -> {
                        editMessageReplyMarkup(chatId, messageId, null)
                        showCategoriesMenu(chatId)
                    }
                    "confirm_order" -> {
                        handleOrderConfirmation(callbackQuery)
                    }
                    "cancel_order" -> {
                        execute(EditMessageText().apply {
                            setChatId(chatId.toString())
                            setMessageId(messageId)
                            text = localizedMessageService.getMessage(MessageKey.ORDER_CANCELLED, chatId)
                            replyMarkup = null
                        })
                        showMainMenu(chatId)
                    }
                    "add_address" -> {
                        editMessageReplyMarkup(chatId, messageId, null)
                        enterAddress(chatId)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error handling callback query: ${e.message}", e)
            sendMessage(chatId, localizedMessageService.getMessage(MessageKey.ERROR_GENERIC, chatId))
        }
    }

    private fun editMessageReplyMarkup(chatId: Long, messageId: Int, replyMarkup: InlineKeyboardMarkup?) {
        try {
            execute(EditMessageReplyMarkup().apply {
                setChatId(chatId.toString())
                setMessageId(messageId)
                setReplyMarkup(replyMarkup)
            })
        } catch (e: TelegramApiException) {
            logger.warn("Failed to edit reply markup for message $messageId in chat $chatId: ${e.message}")
        }
    }

    private fun handleAddressSelection(callbackQuery: CallbackQuery) {
        val chatId = callbackQuery.message?.chatId ?: return
        val data = callbackQuery.data

        if (data.startsWith("select_address:")) {
            val addressId = data.substringAfter("select_address:").toLong()
            orderManager.setAddressId(chatId, addressId)
            sendLocalizedMessage(MessageKey.ADDRESS_SELECTED, chatId)
            setState(chatId, BotState.REGISTERED)
            showMainMenu(chatId)
        } else if (data == "add_address") {
            setState(chatId, BotState.AWAITING_ADDRESS)
            enterAddress(chatId)
        }
    }

    private fun handleAddressRequest(chatId: Long) {
        val userDetails = stateManager.getUser(chatId)

        val existingAddresses = try {
            addressService.getUserAddresses(userDetails?.id!!)
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
                text = localizedMessageService.getMessage(MessageKey.BUTTON_ADD_NEW_ADDRESS, chatId)
                callbackData = "add_address"
            }))

            markup.keyboard = rows
            sendMessage(chatId, SendMessage().apply {
                text = localizedMessageService.getMessage(MessageKey.ADDRESS_SELECT_OR_ADD, chatId)
                replyMarkup = markup
            })
        } else {
            setState(chatId, BotState.AWAITING_ADDRESS)
            sendMessage(chatId, localizedMessageService.getMessage(MessageKey.BUTTON_ADD_NEW_ADDRESS, chatId))
        }
    }

    private fun handleTextMessage(chatId: Long, text: String, currentState: BotState) {
        when (text) {
            "/start" -> {
                handleStartCommand(chatId)
                return
            }

            "/help" -> {
                sendLocalizedMessage(HELP_TEXT_KEY, chatId)
                return
            }

            "/settings" -> {
                if (currentState == BotState.REGISTERED) {
                    showSettingsMenu(chatId)
                } else {
                    sendLocalizedMessage(
                        MessageKey.ERROR_COMMAND_UNAVAILABLE,
                        chatId,
                        text
                    )
                }
                return
            }

            "/menu" -> {
                if (currentState == BotState.REGISTERED) {
                    showMainMenu(chatId)
                } else {
                    sendLocalizedMessage(MessageKey.ERROR_COMMAND_UNAVAILABLE, chatId, text)
                }
                return
            }

            "/deletedata" -> {
                if (currentState == BotState.REGISTERED) {
                    promptForDataDeletion(chatId)
                } else {
                    sendLocalizedMessage(MessageKey.ERROR_COMMAND_UNAVAILABLE, chatId, text)
                }
                return
            }
        }

        if (currentState == BotState.REGISTERED || currentState.name.startsWith("MENU_")) {
            val menuButtonText = localizedMessageService.getMessage(MessageKey.BUTTON_MENU, chatId)
            val cartButtonText = localizedMessageService.getMessage(MessageKey.BUTTON_CART, chatId)
            val addressesButtonText = localizedMessageService.getMessage(MessageKey.BUTTON_MY_ADDRESSES, chatId)
            val settingsButtonText = localizedMessageService.getMessage(MessageKey.BUTTON_SETTINGS, chatId)
            val returnButtonText = localizedMessageService.getMessage(MessageKey.BUTTON_RETURN, chatId)
            val myOrdersButton = localizedMessageService.getMessage(MessageKey.MY_ORDERS, chatId)

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
                            sendLocalizedMessage(MessageKey.UNKNOWN_COMMAND, chatId, text)
                        }
                    } else if (currentMenuState == MenuStates.PRODUCT_VIEW) {
                        val productId = findProductIdByName(chatId, text)
                        if (productId != null) {
                            showProductDetailsOrAddToCart(chatId, productId)
                        } else {
                            sendLocalizedMessage(MessageKey.UNKNOWN_COMMAND, chatId, text)
                        }
                    } else {
                        sendLocalizedMessage(MessageKey.UNKNOWN_COMMAND, chatId, text)
                    }
                }
            }
        } else {
            when (currentState) {
                BotState.AWAITING_LANGUAGE -> sendLocalizedMessage(MessageKey.ERROR_SELECT_LANGUAGE_FIRST, chatId)
                BotState.AWAITING_PHONE -> sendLocalizedMessage(MessageKey.ERROR_SHARE_CONTACT_FIRST, chatId)
                BotState.AWAITING_OTP -> sendLocalizedMessage(MessageKey.ERROR_ENTER_OTP_FIRST, chatId)
                BotState.AWAITING_ADDRESS -> sendLocalizedMessage(MessageKey.ERROR_SHARE_LOCATION_FIRST, chatId)
                BotState.PRODUCT_DETAIL_VIEW -> sendLocalizedMessage(MessageKey.ERROR_SELECT_PRODUCT_FIRST, chatId)
                else -> sendLocalizedMessage(MessageKey.UNKNOWN_COMMAND, chatId, text)
            }
        }
    }

    private fun handleStartCommand(chatId: Long) {
        try {
            val existingUser = userRepository.findByTelegramChatId(chatId)

            // Check if user is fully registered
            if (existingUser != null && existingUser.phoneNumber.isNotBlank()) {
                // User is registered - welcome them back and show main menu
                stateManager.setTemporaryData(chatId, "phone_verified", "true")
                setState(chatId, BotState.REGISTERED)

                // Send welcome back message
                sendLocalizedMessage(MessageKey.WELCOME_BACK, chatId, existingUser.username)

                // Show main menu
                showMainMenu(chatId)
                return
            }

            // Otherwise clear data and start registration process
            clearTemporaryData(chatId)

            if (existingUser != null) {
                stateManager.setTemporaryData(chatId, "username", existingUser.username)
            }

            setState(chatId, BotState.START)
            promptForLanguageSelection(chatId)
        } catch (e: Exception) {
            logger.error("Error in handleStartCommand: ${e.message}", e)
            setState(chatId, BotState.START)
            promptForLanguageSelection(chatId)
        }
    }

    private fun proceedAfterLanguageSelected(chatId: Long) {
        val existingUser = userRepository.findByTelegramChatId(chatId)

        if (existingUser != null && existingUser.phoneNumber.isNotBlank()) {
            setState(chatId, BotState.REGISTERED)
            sendLocalizedMessage(MessageKey.WELCOME_MESSAGE, chatId, existingUser.username)
            showMainMenu(chatId)
            return
        }

        val welcomeText = localizedMessageService.getMessage(MessageKey.WELCOME_MESSAGE, chatId, "Customer")
        sendMessage(chatId, welcomeText)
        register(chatId)
    }

    private fun register(chatId: Long, firstName: String? = null) {
        try {
            val displayName = if (!firstName.isNullOrBlank()){
                firstName
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
                user = userRepository.save(user)
                stateManager.cacheUser(user)

            } else {
                stateManager.cacheUser(user)
            }
        } catch (e: Exception) {
            logger.error("Error ensuring user exists during registration for chat $chatId: ${e.message}", e)
            sendLocalizedMessage(MessageKey.ERROR_GENERIC, chatId)
            return
        }

        val message = SendMessage()
        message.chatId = chatId.toString()
        message.text = localizedMessageService.getMessage(
            MessageKey.REGISTER_PROMPT_PHONE,
            chatId
        )

        val markup = ReplyKeyboardMarkup().apply {
            keyboard = listOf(
                KeyboardRow().apply {
                    add(
                        KeyboardButton(
                            localizedMessageService.getMessage(
                                MessageKey.BUTTON_REGISTER_SHARE_PHONE,
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

    private fun promptForLanguageSelection(chatId: Long) {
        val message = SendMessage()
        message.chatId = chatId.toString()
        val promptText = localizedMessageService.getMessage(MessageKey.LANGUAGE_SELECT, Locale.forLanguageTag("uz"))

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
                sendLocalizedMessage(MessageKey.ERROR_INVALID_CONTACT, chatId)
                register(chatId)
                return
            }

            val response = authService.requestOtp(OtpRequest(phoneNumber), chatId)

            setTemporaryData(chatId, "phone_number", phoneNumber)
            setTemporaryData(chatId, "otp_id", response.smsCodeId.toString())

            val message = SendMessage()
            message.chatId = chatId.toString()
            message.text = localizedMessageService.getMessage(MessageKey.OTP_PROMPT, chatId)
            message.replyMarkup = ForceReplyKeyboard().apply {
                forceReply = true
                selective = true
            }

            execute(message)
            setState(chatId, BotState.AWAITING_OTP)
        } catch (e: Exception) {
            logger.error("Error requesting OTP: ${e.message}", e)
            sendLocalizedMessage(MessageKey.ERROR_OTP_REQUEST_FAILED, chatId)
            register(chatId)
        }
    }

    private fun handleOtpReply(chatId: Long, otp: String) {
        val phoneNumber = getTemporaryData(chatId, "phone_number")
        if (phoneNumber == null) {
            sendLocalizedMessage(MessageKey.ERROR_GENERIC, chatId)
            setState(chatId, BotState.START)
            handleStartCommand(chatId)
            return
        }

        val otpIdString = getTemporaryData(chatId, "otp_id")
        if (otpIdString == null) {
            sendLocalizedMessage(MessageKey.ERROR_OTP_EXPIRED_OR_MISSING, chatId)
            setState(chatId, BotState.START)
            handleStartCommand(chatId)
            return
        }

        val otpId = otpIdString.toLongOrNull()
        if (otpId == null) {
            sendLocalizedMessage(MessageKey.ERROR_GENERIC, chatId)
            setState(chatId, BotState.START)
            handleStartCommand(chatId)
            return
        }

        try {
            authService.otpLogin(OtpLogin(phoneNumber, otp, otpId), chatId)

            val user = getCurrentUser(chatId)
            if (user != null) {
                user.phoneNumber = phoneNumber
                userRepository.save(user)

                stateManager.setTemporaryData(chatId, "phone_verified", "true")
            }

            clearTemporaryData(chatId)
            enterAddress(chatId)
        } catch (e: Exception) {
            logger.error("OTP validation failed: ${e.message}", e)
            sendLocalizedMessage(MessageKey.ERROR_OTP_INVALID, chatId)

            requestOtpAgain(chatId, phoneNumber)
        }
    }

    private fun requestOtpAgain(chatId: Long, phoneNumber: String) {
        try {
            val response = authService.requestOtp(OtpRequest(phoneNumber), chatId)

            setTemporaryData(chatId, "otp_id", response.smsCodeId.toString())

            setState(chatId, BotState.AWAITING_OTP)

            val message = SendMessage()
            message.chatId = chatId.toString()
            message.text = localizedMessageService.getMessage(MessageKey.OTP_RETRY_PROMPT, chatId)
            message.replyMarkup = ForceReplyKeyboard().apply {
                forceReply = true
                selective = true
            }

            execute(message)
        } catch (e: Exception) {
            logger.error("Error requesting new OTP: ${e.message}", e)
            sendLocalizedMessage(MessageKey.ERROR_OTP_REQUEST_FAILED, chatId)
            register(chatId)
        }
    }

    private fun enterAddress(chatId: Long) {
        val message = SendMessage()
        message.chatId = chatId.toString()
        message.text = 
            localizedMessageService.getMessage(MessageKey.ADDRESS_PROMPT, chatId)

        val markup = ReplyKeyboardMarkup().apply {
            keyboard = listOf(
                KeyboardRow().apply {
                    add(KeyboardButton(localizedMessageService.getMessage(MessageKey.BUTTON_SHARE_LOCATION, chatId)).apply {
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

    private fun saveUserAddress(chatId: Long, latitude: Double, longitude: Double, addressDetails: String) {
        val user = userRepository.findByTelegramChatId(chatId)
        if (user == null) {
            sendLocalizedMessage(MessageKey.ERROR_AUTH_REQUIRED, chatId)
            setState(chatId, BotState.START)
            handleStartCommand(chatId)
            return
        }

        try {
            val parts = addressDetails.split(",")
            val addressLine = parts[0].trim()
            val city = if (parts.size > 1) parts[1].trim() else "Unknown"

            val addressRequest = AddressRequest(
                addressLine = addressLine,
                city = city,
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

            sendLocalizedMessage(MessageKey.ADDRESS_SAVED, chatId)
            setState(chatId, BotState.REGISTERED)
            showMainMenu(chatId)
        } catch (e: Exception) {
            logger.error("Failed to save address: ${e.message}", e)
            sendLocalizedMessage(MessageKey.ERROR_ADDRESS_SAVE_FAILED, chatId)
            enterAddress(chatId)
        }
    }

    private fun showMainMenu(chatId: Long) {
        setState(chatId, BotState.REGISTERED)

        val message = SendMessage()
        message.chatId = chatId.toString()
        message.text = localizedMessageService.getMessage(MessageKey.MAIN_MENU_PROMPT, chatId)

        val markup = ReplyKeyboardMarkup().apply {
            keyboard = listOf(
                KeyboardRow().apply {
                    add(KeyboardButton(localizedMessageService.getMessage(MessageKey.BUTTON_MENU, chatId)))
                    add(KeyboardButton(localizedMessageService.getMessage(MessageKey.BUTTON_CART, chatId)))
                },
                KeyboardRow().apply {
                    add(KeyboardButton(localizedMessageService.getMessage(MessageKey.BUTTON_MY_ADDRESSES, chatId)))
                    add(KeyboardButton(localizedMessageService.getMessage(MessageKey.BUTTON_SETTINGS, chatId)))
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
        backRow.add(KeyboardButton(localizedMessageService.getMessage(MessageKey.BUTTON_RETURN, chatId)))
        keyboard.add(backRow)

        replyKeyboardMarkup.keyboard = keyboard
        replyKeyboardMarkup.resizeKeyboard = true

        val message = SendMessage()
        message.chatId = chatId.toString()
        message.text = localizedMessageService.getMessage(MessageKey.CATEGORY_CHOOSE, chatId)
        message.replyMarkup = replyKeyboardMarkup

        execute(message)
    }

    private fun showProductMenu(chatId: Long, categoryId: Long) {
        setPreviousState(chatId, getCurrentState(chatId))
        setMenuState(chatId, MenuStates.PRODUCT_VIEW)

        val message = SendMessage()
        message.chatId = chatId.toString()

        val markup = InlineKeyboardMarkup()
        val keyboardRows = mutableListOf<List<InlineKeyboardButton>>()
        val products = productService.getProductsByCategoryId(categoryId)

        if (products.isEmpty()) {
            message.text = localizedMessageService.getMessage(MessageKey.PRODUCT_NONE_IN_CATEGORY, chatId)
        } else {
            message.text = localizedMessageService.getMessage(MessageKey.PRODUCT_CHOOSE, chatId)

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

        val returnButton = InlineKeyboardButton(localizedMessageService.getMessage(MessageKey.BUTTON_RETURN, chatId))
        returnButton.callbackData = "main_menu"
        keyboardRows.add(listOf(returnButton))

        markup.keyboard = keyboardRows
        message.replyMarkup = markup
        sendMessage(chatId, message)
    }

    private fun handleProductSelection(chatId: Long, productId: Long) {
        val product = productService.getProductById(productId) ?: run {
            sendMessage(chatId, localizedMessageService.getMessage(MessageKey.ERROR_PRODUCT_NOT_FOUND, chatId))
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
                    text = localizedMessageService.getMessage(MessageKey.BUTTON_ADD_TO_CART, chatId)
                    callbackData = "add_to_cart:$productId:1"
                }
            ),
            listOf(
                InlineKeyboardButton().apply {
                    text = localizedMessageService.getMessage(MessageKey.BUTTON_RETURN, chatId)
                    callbackData = "back_to_products"
                }
            )
        )

        val productName = getLocalizedProductName(product, chatId)
        val message = SendMessage()
        message.chatId = chatId.toString()
        message.text = "$productName\nPrice: ${product.price} UZS\nQuantity: 1"
        message.replyMarkup = markup

        execute(message)
    }

    private fun handleQuantityAdjustment(callbackQuery: CallbackQuery) {
        val chatId = callbackQuery.message?.chatId ?: return
        val messageId = callbackQuery.message?.messageId ?: return
        
        val parts = callbackQuery.data.split(":")
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
                try {
                    cartService.addItemToCart(chatId, productId, currentQuantity)
                    val confirmationText = localizedMessageService.getMessage(MessageKey.CART_ADDED_ITEMS, chatId, currentQuantity)
                    execute(EditMessageText().apply {
                        setChatId(chatId.toString())
                        setMessageId(messageId)
                        text = confirmationText
                        replyMarkup = null
                    })
                    handleViewCart(chatId)
                } catch (e: Exception) {
                    logger.error("Error adding item to cart or editing message: ${e.message}", e)
                    sendLocalizedMessage(MessageKey.ERROR_CART_ADD_FAILED, chatId)
                }
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
                    text = localizedMessageService.getMessage(MessageKey.BUTTON_ADD_TO_CART, chatId)
                    callbackData = "add_to_cart:$productId:$currentQuantity"
                }
            ),
            listOf(
                InlineKeyboardButton().apply {
                    text = localizedMessageService.getMessage(MessageKey.BUTTON_RETURN, chatId)
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
                sendLocalizedMessage(MessageKey.CART_EMPTY, chatId)
                return
            }

            val messageBuilder = StringBuilder(localizedMessageService.getMessage(MessageKey.CART_TITLE, chatId))
            messageBuilder.append("\n\n")

            var total = 0.0
            cart.items.forEach { item ->
                val productName = getLocalizedProductName(item.product, chatId)
                val itemTotal = item.quantity.toDouble().times(item.product.price.toDouble())
                total += itemTotal

                messageBuilder.append("$productName x ${item.quantity} = ${itemTotal}\n")
            }

            messageBuilder.append("\n${localizedMessageService.getMessage(MessageKey.CART_TOTAL, chatId)}: $total")

            val message = SendMessage()
            message.chatId = chatId.toString()
            message.text = messageBuilder.toString()

            val markup = InlineKeyboardMarkup()
            val keyboardRows = mutableListOf<List<InlineKeyboardButton>>()

            val checkoutButton = InlineKeyboardButton(localizedMessageService.getMessage(MessageKey.BUTTON_CHECKOUT, chatId))
            checkoutButton.callbackData = "checkout"

            val continueButton =
                InlineKeyboardButton(localizedMessageService.getMessage(MessageKey.BUTTON_CONTINUE_SHOPPING, chatId))
            continueButton.callbackData = "main_menu"

            keyboardRows.add(listOf(checkoutButton))
            keyboardRows.add(listOf(continueButton))

            markup.keyboard = keyboardRows
            message.replyMarkup = markup

            sendMessage(chatId, message)
        } catch (e: Exception) {
            sendLocalizedMessage(MessageKey.ERROR_CART_VIEW_FAILED, chatId)
        }
    }

    private fun handleCheckout(chatId: Long) {
        try {
            val cart = cartService.getCart(chatId)

            if (cart.items.isEmpty()) {
                sendLocalizedMessage(MessageKey.CART_EMPTY, chatId)
                return
            }

            val user = userRepository.findByTelegramChatId(chatId)
            if (user == null) {
                sendLocalizedMessage(MessageKey.ERROR_AUTH_REQUIRED, chatId)
                setState(chatId, BotState.START)
                handleStartCommand(chatId)
                return
            }

            val addresses = addressRepository.findAllByUserIdAndDeletedFalse(user.id!!)

            if (addresses.isEmpty()) {
                sendLocalizedMessage(MessageKey.CHECKOUT_NO_ADDRESSES, chatId)
                enterAddress(chatId)
                return
            }

            val message = SendMessage()
            message.chatId = chatId.toString()
            message.text = localizedMessageService.getMessage(MessageKey.CHECKOUT_SELECT_ADDRESS, chatId)

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
                text = localizedMessageService.getMessage(MessageKey.BUTTON_ADD_NEW_ADDRESS, chatId)
                callbackData = "add_address"
            }
            keyboardRows.add(listOf(addNewAddressButton))

            markup.keyboard = keyboardRows
            message.replyMarkup = markup

            sendMessage(chatId, message)
            setState(chatId, BotState.AWAITING_ADDRESS_SELECTION)
        } catch (e: Exception) {
            logger.error("Error during checkout: ${e.message}", e)
            sendLocalizedMessage(MessageKey.ERROR_CHECKOUT, chatId)
        }
    }

    private fun proceedToOrderConfirmation(chatId: Long) {
        val cart = cartService.getCart(chatId)
        if (cart.items.isEmpty()) {
            sendMessage(chatId, localizedMessageService.getMessage(MessageKey.CART_EMPTY, chatId))
            return
        }

        showOrderConfirmation(chatId, cart)
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
            logger.error("Failed to send message to chat $chatId: ${e.message}")
        }
    }

    private fun sendMessage(chatId: Long, message: SendMessage) {
        message.chatId = chatId.toString()
        try {
            execute(message)
        } catch (e: TelegramApiException) {
             sendMessage(chatId, localizedMessageService.getMessage(MessageKey.ERROR_SEND_FAILED, chatId))
        }
    }
    
    private fun sendLocalizedMessage(key: MessageKey, chatId: Long, vararg args: Any) {
        val localizedText = localizedMessageService.getMessage(key, chatId, *args)
        sendMessage(chatId, localizedText)
    }

    private fun getLocalizedProductName(product: ProductDTO, chatId: Long): String {
        val locale = stateManager.getUserLocale(chatId)
        return when (locale.language) {
            "uz" -> product.nameUz ?: product.name
            "ru" -> product.nameRu ?: product.name
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
                text = localizedMessageService.getMessage(MessageKey.BUTTON_RETURN, chatId)
                callbackData = "main_menu"
            }
        ))

        markup.keyboard = keyboardRows

        sendMessage(chatId, SendMessage().apply {
            text = localizedMessageService.getMessage(MessageKey.LANGUAGE_SELECT, chatId)
            replyMarkup = markup
        })
    }

    private fun showCart(chatId: Long) {
        handleViewCart(chatId)
    }

    private fun showAddresses(chatId: Long) {
        val user = getCurrentUser(chatId) ?: run {
            sendLocalizedMessage(MessageKey.ERROR_AUTH_REQUIRED, chatId)
            return
        }

        val addresses = addressRepository.findAllByUserIdAndDeletedFalse(user.id!!)

        if (addresses.isEmpty()) {
            sendLocalizedMessage(MessageKey.ADDRESS_NONE, chatId)

            val message = SendMessage()
            message.chatId = chatId.toString()

            val markup = InlineKeyboardMarkup()
            markup.keyboard = listOf(
                listOf(
                    InlineKeyboardButton().apply {
                        text = localizedMessageService.getMessage(MessageKey.BUTTON_ADD_NEW_ADDRESS, chatId)
                        callbackData = "add_address"
                    }
                ),
                listOf(
                    InlineKeyboardButton().apply {
                        text = localizedMessageService.getMessage(MessageKey.BUTTON_MAIN_MENU, chatId)
                        callbackData = "main_menu"
                    }
                )
            )

            message.replyMarkup = markup
            sendMessage(chatId, message)
            return
        }

        val messageBuilder = StringBuilder(localizedMessageService.getMessage(MessageKey.ADDRESS_LIST_TITLE, chatId))
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
                text = localizedMessageService.getMessage(MessageKey.BUTTON_ADD_NEW_ADDRESS, chatId)
                callbackData = "add_address"
            }
        ))

        keyboardRows.add(listOf(
            InlineKeyboardButton().apply {
                text = localizedMessageService.getMessage(MessageKey.BUTTON_MAIN_MENU, chatId)
                callbackData = "main_menu"
            }
        ))

        markup.keyboard = keyboardRows

        sendMessage(chatId, SendMessage().apply {
            text = messageBuilder.toString()
            replyMarkup = markup
        })
    }

    private fun showProductDetailsOrAddToCart(chatId: Long, productId: Long) {
        val product = productService.getProductById(productId) ?: run {
            sendLocalizedMessage(MessageKey.ERROR_PRODUCT_NOT_FOUND, chatId)
            return
        }

        val localizedName = getLocalizedProductName(product, chatId)
        val messageBuilder = StringBuilder("*$localizedName*\n\n")

        messageBuilder.append("${product.description}\n\n")
        messageBuilder.append("*${localizedMessageService.getMessage(MessageKey.PRICE, chatId)}: ${product.price} UZS*")

        val markup = InlineKeyboardMarkup()
        markup.keyboard = listOf(
            listOf(
                InlineKeyboardButton().apply {
                    text = localizedMessageService.getMessage(MessageKey.BUTTON_ADD_TO_CART, chatId)
                    callbackData = "add_to_cart:${product.id}:1"
                }
            ),
            listOf(
                InlineKeyboardButton().apply {
                    text = localizedMessageService.getMessage(MessageKey.BUTTON_RETURN, chatId)
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

        messageBuilder.apply {
            append("\n*Subtotal:* $subtotal UZS")
            append("\n*Service Fee (5%):* $serviceCharge UZS")
            append("\n*Delivery Fee:* $deliveryFee UZS")
            append("\n*Discount:* -$discount UZS")
            append("\n\n*Total:* $total UZS")
            append("\n\nPayment: Cash on Delivery")
        }

        val markup = InlineKeyboardMarkup()
        markup.keyboard = listOf(
            listOf(
                InlineKeyboardButton().apply {
                    text = "✅ " + localizedMessageService.getMessage(MessageKey.BUTTON_CONFIRM_ORDER, chatId)
                    callbackData = "confirm_order"
                },
                InlineKeyboardButton().apply {
                    text = "❌ " + localizedMessageService.getMessage(MessageKey.BUTTON_CANCEL, chatId)
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
        val messageId = callbackQuery.message?.messageId ?: return // Get messageId

        try {
            val cart = cartService.getCart(chatId)
            if (cart.items.isEmpty()) {
                sendMessage(chatId, localizedMessageService.getMessage(MessageKey.CART_EMPTY, chatId))
                return
            }

            val user = userRepository.findByTelegramChatId(chatId)
            if (user == null) {
                sendLocalizedMessage(MessageKey.ERROR_AUTH_REQUIRED, chatId)
                setState(chatId, BotState.START)
                handleStartCommand(chatId)
                return
            }

            val addressId = orderManager.getAddressId(chatId) ?: run {
                sendMessage(chatId, localizedMessageService.getMessage(MessageKey.ERROR_ADDRESS_REQUIRED, chatId))
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
                val messageText = StringBuilder(localizedMessageService.getMessage(MessageKey.ORDER_CONFIRMED, chatId))

                cartService.clearCart(chatId)

                execute(EditMessageText().apply {
                    setChatId(chatId.toString())
                    setMessageId(messageId)
                    text = messageText.toString()
                    replyMarkup = null
                    enableMarkdown(true)
                })

                showMainMenu(chatId)
            } catch (e: Exception) {
                logger.error("Failed to create order: ${e.message}", e)
                execute(EditMessageText().apply {
                    setChatId(chatId.toString())
                    setMessageId(messageId)
                    text = localizedMessageService.getMessage(MessageKey.ORDER_FAILED, chatId)
                    replyMarkup = null 
                })            }
        } catch (e: Exception) {
            logger.error("Error confirming order: ${e.message}", e)
            execute(EditMessageText().apply {
                setChatId(chatId.toString())
                setMessageId(messageId)
                text = localizedMessageService.getMessage(MessageKey.ERROR_GENERIC, chatId)
                replyMarkup = null // Remove confirm/cancel buttons
            })
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
            val categoryIdString = getTemporaryData(chatId, "current_category_id")
            val categoryId = categoryIdString?.toLongOrNull()

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

        return null
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

    private fun getCurrentState(chatId: Long): BotState {
        return stateManager.getCurrentState(chatId)
    }

    private fun setState(chatId: Long, state: BotState) {
        stateManager.setState(chatId, state)
    }

    private fun getPreviousState(chatId: Long): BotState {
        return stateManager.getPreviousState(chatId)
    }

    private fun setPreviousState(chatId: Long, state: BotState) {
        stateManager.setPreviousState(chatId, state)
    }

    private fun getCurrentMenuState(chatId: Long): MenuStates {
        return stateManager.getMenuState(chatId)
    }

    private fun setMenuState(chatId: Long, state: MenuStates) {
        stateManager.setMenuState(chatId, state)
    }

    private fun getTemporaryData(chatId: Long, key: String): String? {
        return stateManager.getTemporaryData(chatId, key)
    }

    private fun setTemporaryData(chatId: Long, key: String, value: String) {
        stateManager.setTemporaryData(chatId, key, value)
    }

    private fun clearTemporaryData(chatId: Long) {
        stateManager.clearTemporaryData(chatId)
    }

    private fun getCurrentUser(chatId: Long): User? {
        return stateManager.getUser(chatId)
    }

    private fun getUserId(chatId: Long): Long {
        return stateManager.getUser(chatId)?.id
            ?: throw ResourceNotFoundException("User not found for chat ID: $chatId")
    }

    private fun getUserLocale(chatId: Long): String {
        return stateManager.getUserLocale(chatId).language
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
    private val addressRepository: AddressRepository,
    private val stateManager: InMemoryStateManager // Add this
) {
    fun setAddressId(chatId: Long, addressId: Long) {
        val user = stateManager.getUser(chatId)
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
        val user = stateManager.getUser(chatId)
            ?: throw ResourceNotFoundException(chatId)

        val orderData = orderDataRepository.findByUserIdAndDeletedFalse(user.id!!)
        return orderData?.address?.id
    }
}
