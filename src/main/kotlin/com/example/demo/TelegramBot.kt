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
import org.springframework.data.domain.PageRequest
import org.telegram.telegrambots.meta.TelegramBotsApi
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageCaption
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove
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
    private val stateManager: InMemoryStateManager,
    private val geocodingService: GeocodingService
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
                                MessageKey.LOCATION_RECEIVED_PROCESSING, chatId // New message key
                            ))

                            saveUserAddress(chatId, location.latitude, location.longitude)
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
            logger.warn(e.message)
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
                val action = parts[0]

                when (action) {
                    "increase_quantity", "decrease_quantity", "add_to_cart" -> {
                        if (parts.size >= 3) {
                            handleQuantityAdjustment(callbackQuery)
                        } else {
                            answerCallbackQuery(callbackQuery.id, localizedMessageService.getMessage(MessageKey.ERROR_GENERIC, chatId))
                        }
                    }

                    "address" -> {
                            if (parts.size >= 2) {
                            val addressId = parts[1].toLong()
                            editMessageReplyMarkup(chatId, messageId, null)
                            processOrderWithAddress(chatId, addressId) // Proceeds to confirmation
                            answerCallbackQuery(callbackQuery.id)
                        } else {
                            logger.warn("Invalid address callback data: $data")
                            answerCallbackQuery(callbackQuery.id, localizedMessageService.getMessage(MessageKey.ERROR_GENERIC, chatId))
                        }
                    }

                    "select_address" -> {
                        if (parts.size >= 2) {
                            val addressId = parts[1].toLong()
                            execute(EditMessageText().apply {
                                setChatId(chatId.toString())
                                setMessageId(messageId)
                                text = localizedMessageService.getMessage(MessageKey.ADDRESS_SELECTED, chatId) + "\nID: ${addressId}" // Example confirmation
                                replyMarkup = null
                            })
                            showMainMenu(chatId)
                            answerCallbackQuery(callbackQuery.id)
                        } else {
                            logger.warn("Invalid select_address callback data: $data")
                            answerCallbackQuery(callbackQuery.id, localizedMessageService.getMessage(MessageKey.ERROR_GENERIC, chatId))
                        }
                    }

                    "set_lang" -> {
                        if (parts.size >= 2) {
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
                            answerCallbackQuery(callbackQuery.id)
                        } else {
                            logger.warn("Invalid set_lang callback data: $data")
                            answerCallbackQuery(callbackQuery.id, localizedMessageService.getMessage(MessageKey.ERROR_GENERIC, chatId))
                        }
                    }

                    "view_order" -> {
                        if (parts.size >= 2) {
                            val orderId = parts[1].toLong()
                            showOrderDetails(chatId, orderId, messageId)
                        } else {
                            logger.warn("Invalid view_order callback data: $data")
                            answerCallbackQuery(callbackQuery.id, localizedMessageService.getMessage(MessageKey.ERROR_GENERIC, chatId))
                        }
                    }

                    "confirm_delete_data" -> {
                        logger.warn("confirm_delete_data should not contain a colon. Data: $data")
                        answerCallbackQuery(callbackQuery.id, localizedMessageService.getMessage(MessageKey.ERROR_GENERIC, chatId))
                    }

                    else -> {
                        logger.warn("Unhandled callback data with colon: $data")
                        answerCallbackQuery(callbackQuery.id, localizedMessageService.getMessage(MessageKey.UNKNOWN_COMMAND, chatId))
                    }
                }
            } else {
                when (data) {
                    "main_menu" -> {
                        try {
                            execute(EditMessageText().apply {
                                setChatId(chatId.toString())
                                setMessageId(messageId)
                                text = localizedMessageService.getMessage(MessageKey.RETURNING_TO_MENU, chatId)
                                replyMarkup = null
                            })
                        } catch (e: TelegramApiException) {
                            logger.warn("Failed to edit message for main_menu callback: ${e.message}")
                            try {
                                editMessageReplyMarkup(chatId, messageId, null)
                            } catch (e2: TelegramApiException) {
                                logger.warn("Also failed to remove markup: ${e2.message}")
                            }
                        }
                        showMainMenu(chatId)
                        answerCallbackQuery(callbackQuery.id)
                    }

                    "view_cart" -> {
                        editMessageReplyMarkup(chatId, messageId, null)
                        handleViewCart(chatId)
                        answerCallbackQuery(callbackQuery.id)
                    }

                    "checkout" -> {
                        editMessageReplyMarkup(chatId, messageId, null)
                        handleCheckout(chatId) // Shows address selection
                        answerCallbackQuery(callbackQuery.id)
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
                        answerCallbackQuery(callbackQuery.id)
                    }

                    "add_address" -> {
                        try {
                            execute(EditMessageText().apply {
                                setChatId(chatId.toString())
                                setMessageId(messageId)
                                text = localizedMessageService.getMessage(MessageKey.ADDRESS_PROMPT, chatId)
                                replyMarkup = null
                            })
                        } catch (e: TelegramApiException) {
                            logger.warn("Failed to edit message for add_address callback: ${e.message}")
                        }
                        enterAddress(chatId)
                        answerCallbackQuery(callbackQuery.id)
                    }
                    "back_to_product" -> {
                        val productId = getTemporaryData(chatId, "current_product_id")?.toLongOrNull()
                        if (productId != null) {
                            try {
                                execute(EditMessageText().apply {
                                    setChatId(chatId.toString())
                                    setMessageId(messageId)
                                    text = localizedMessageService.getMessage(MessageKey.RETURNING_TO_PRODUCT, chatId)
                                    replyMarkup = null // Remove inline buttons
                                })
                            } catch (e: TelegramApiException) {
                                logger.warn("Failed to edit message: ${e.message}")
                                try {
                                    editMessageReplyMarkup(chatId, messageId, null)
                                } catch (e2: TelegramApiException) {
                                    logger.warn("Also failed to remove markup: ${e2.message}")
                                }
                            }

                            showProductDetails(chatId, productId)
                            answerCallbackQuery(callbackQuery.id)
                        } else {
                            logger.warn("No current product ID found for chat $chatId")
                            answerCallbackQuery(callbackQuery.id, "Cannot return to product details")
                            showCategoriesMenu(chatId)
                        }
                    }

                    "quantity_info" -> {
                        answerCallbackQuery(callbackQuery.id, "Current quantity")
                    }

                    "clear_cart" -> {
                        try {
                            cartService.clearCart(chatId)

                            execute(EditMessageText().apply {
                                setChatId(chatId.toString())
                                setMessageId(messageId)
                                text = localizedMessageService.getMessage(MessageKey.CART_CLEARED, chatId)
                                replyMarkup = null
                            })

                            showMainMenu(chatId)

                            answerCallbackQuery(callbackQuery.id, localizedMessageService.getMessage(MessageKey.CART_CLEARED, chatId))

                        } catch (e: Exception) {
                            logger.error("Error clearing cart for chat $chatId: ${e.message}", e)
                            answerCallbackQuery(callbackQuery.id, "Error clearing cart")
                            try {
                                execute(EditMessageText().apply {
                                    setChatId(chatId.toString())
                                    setMessageId(messageId)
                                    text = localizedMessageService.getMessage(MessageKey.ERROR_GENERIC, chatId) // Or a specific cart error key
                                    replyMarkup = null
                                })
                            } catch (editEx: TelegramApiException) {
                                logger.error("Failed to edit message on cart clear error: ${editEx.message}")
                            }
                        }
                    }

                    "show_orders" -> {
                        try {
                            execute(EditMessageText().apply {
                                setChatId(chatId.toString())
                                setMessageId(messageId)
                                text = localizedMessageService.getMessage(MessageKey.RETURNING_TO_ORDERS, chatId)
                                replyMarkup = null
                            })
                        } catch (e: TelegramApiException) {
                            logger.warn("Failed to edit message for show_orders callback: ${e.message}")
                        }
                        showUserOrders(chatId)
                        answerCallbackQuery(callbackQuery.id)
                    }

                    "confirm_delete_data" -> {
                        try {
                            logger.info("User $chatId confirmed data deletion.")
                            // userRepository.deleteByTelegramChatId(chatId) // Example deletion

                            execute(EditMessageText().apply {
                                setChatId(chatId.toString())
                                setMessageId(messageId)
                                text = "Your data has been deleted."
                                replyMarkup = null
                            })
                            setState(chatId, BotState.START)
                            answerCallbackQuery(callbackQuery.id, "Data deleted.")
                        } catch (e: Exception) {
                            logger.error("Error deleting user data for chat $chatId: ${e.message}", e)
                            answerCallbackQuery(callbackQuery.id, "Error deleting data.")
                        }
                    }

                    else -> {
                        logger.warn("Unhandled callback data without colon: $data")
                        answerCallbackQuery(callbackQuery.id, "Unknown action")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error handling callback query '$data' for chat $chatId: ${e.message}", e)
            try {
                answerCallbackQuery(callbackQuery.id, localizedMessageService.getMessage(MessageKey.ERROR_GENERIC, chatId))
            } catch (ignore: Exception) {
                logger.debug(ignore.message)
            }
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

    private fun handleAddressRequest(chatId: Long) {
        val userDetails = stateManager.getUser(chatId)

        val existingAddresses = try {
            addressService.getUserAddresses(userDetails?.id!!)
        } catch (e: Exception) {
            logger.warn(e.message)
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
            "/menu" -> {
                if (userRepository.findByTelegramChatId(chatId)?.phoneNumber?.isNotBlank() == true) {
                    setState(chatId, BotState.REGISTERED)
                    setMenuState(chatId, MenuStates.MAIN_MENU)
                    showMainMenu(chatId)
                } else {
                    sendLocalizedMessage(MessageKey.ERROR_COMMAND_UNAVAILABLE, chatId, text)
                }
                return
            }
            "/settings" -> {
                if (currentState == BotState.REGISTERED) {
                    showSettingsMenu(chatId)
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

        if (currentState == BotState.REGISTERED) {
            val menuButtonText = localizedMessageService.getMessage(MessageKey.BUTTON_MENU, chatId)
            val cartButtonText = localizedMessageService.getMessage(MessageKey.BUTTON_CART, chatId)
            val addressesButtonText = localizedMessageService.getMessage(MessageKey.BUTTON_MY_ADDRESSES, chatId)
            val settingsButtonText = localizedMessageService.getMessage(MessageKey.BUTTON_SETTINGS, chatId)
            val returnButtonText = localizedMessageService.getMessage(MessageKey.BUTTON_RETURN, chatId)
            val myOrdersButtonText = localizedMessageService.getMessage(MessageKey.BUTTON_MY_ORDERS, chatId)
            val addToCartButtonText = localizedMessageService.getMessage(MessageKey.BUTTON_ADD_TO_CART, chatId)

            val currentMenuState = getCurrentMenuState(chatId)

            if (currentMenuState == MenuStates.QUANTITY_SELECTOR) {
                when (text) {
                    addToCartButtonText -> {
                        val productIdStr = getTemporaryData(chatId, "current_product_id")
                        val quantityStr = getTemporaryData(chatId, "current_quantity")
                        val productMsgId = getTemporaryData(chatId, "product_message_id")?.toIntOrNull()

                        if (productIdStr != null && quantityStr != null) {
                            val productId = productIdStr.toLong()
                            val quantity = quantityStr.toInt()

                            // Use our new function without messageId
                            handleAddToCartAndGoToCheckout(chatId, productId, quantity, productMsgId)
                        } else {
                            logger.error("Missing product/quantity data for reply 'Add to Cart' in chat $chatId")
                            sendLocalizedMessage(MessageKey.ERROR_GENERIC, chatId)
                        }
                        return
                    }
                    returnButtonText -> {
                        handleReturnAction(chatId)
                        return
                    }
                }
            }
            else if (currentMenuState == MenuStates.ADDRESS_SELECTION) {
                if (text == returnButtonText) {
                    handleReturnAction(chatId)
                    return // Handled
                }
            }

            when (text) {
                menuButtonText -> {
                    setMenuState(chatId, MenuStates.CATEGORY_VIEW)
                    showCategoriesMenu(chatId)
                }
                cartButtonText -> {
                    handleViewCart(chatId)
                }
                addressesButtonText -> {
                    setMenuState(chatId, MenuStates.ADDRESSES_VIEW)
                    showAddresses(chatId)
                }
                settingsButtonText -> {
                    setMenuState(chatId, MenuStates.SETTINGS_VIEW)
                    showSettingsMenu(chatId)
                }
                myOrdersButtonText -> {
                    showUserOrders(chatId)
                }
                returnButtonText -> {
                    if (currentMenuState != MenuStates.QUANTITY_SELECTOR && currentMenuState != MenuStates.ADDRESS_SELECTION) {
                        handleReturnAction(chatId)
                    }
                }
                else -> {
                    val currentMenuState = getCurrentMenuState(chatId)
                    if (currentMenuState == MenuStates.CATEGORY_VIEW) {
                        val categoryId = findCategoryIdByName(chatId, text)
                        if (categoryId != null) {
                            setMenuState(chatId, MenuStates.PRODUCT_VIEW)
                            showProductMenu(chatId, categoryId)
                        } else {
                            sendLocalizedMessage(MessageKey.UNKNOWN_COMMAND, chatId, text)
                        }
                    }
                     else if (currentMenuState == MenuStates.PRODUCT_VIEW) {
                        val productId = findProductIdByName(chatId, text)
                        if (productId != null){
                            showProductDetails(chatId, productId)
                        } else {
                            sendLocalizedMessage(MessageKey.UNKNOWN_COMMAND, chatId, text)
                        }
                     }
                    else if (currentMenuState != MenuStates.ADDRESS_SELECTION || text != returnButtonText) {
                        val knownButtons = listOf(menuButtonText, cartButtonText, addressesButtonText, settingsButtonText, myOrdersButtonText, returnButtonText, addToCartButtonText)
                        if (!knownButtons.contains(text)) {
                            sendLocalizedMessage(MessageKey.UNKNOWN_COMMAND, chatId, text)
                        }
                    }
                    else {
                        val knownButtons = listOf(menuButtonText, cartButtonText, addressesButtonText, settingsButtonText, myOrdersButtonText, returnButtonText, addToCartButtonText)
                        if (!knownButtons.contains(text)) {
                            sendLocalizedMessage(MessageKey.UNKNOWN_COMMAND, chatId, text)
                        }
                    }

                }
            }
        } else {
            when (currentState) {
                BotState.AWAITING_LANGUAGE -> sendLocalizedMessage(MessageKey.ERROR_SELECT_LANGUAGE_FIRST, chatId)
                BotState.AWAITING_PHONE -> sendLocalizedMessage(MessageKey.ERROR_SHARE_CONTACT_FIRST, chatId)
                BotState.AWAITING_ADDRESS -> sendLocalizedMessage(MessageKey.ERROR_SHARE_LOCATION_FIRST, chatId)
                else -> sendLocalizedMessage(MessageKey.UNKNOWN_COMMAND, chatId, text)
            }
        }
    }

    private fun handleStartCommand(chatId: Long) {
        try {
            val existingUser = userRepository.findByTelegramChatId(chatId)

            if (existingUser != null && existingUser.phoneNumber.isNotBlank()) {
                stateManager.setTemporaryData(chatId, "phone_verified", "true")
                setState(chatId, BotState.REGISTERED)

                sendLocalizedMessage(MessageKey.WELCOME_BACK, chatId, existingUser.username)

                showMainMenu(chatId)
                return
            }

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

    private fun saveUserAddress(chatId: Long, latitude: Double, longitude: Double) {
        val user = userRepository.findByTelegramChatId(chatId)
        if (user == null) {
            sendLocalizedMessage(MessageKey.ERROR_AUTH_REQUIRED, chatId)
            setState(chatId, BotState.START)
            handleStartCommand(chatId)
            return
        }

        try {
            val geocodingResult = geocodingService.getAddressFromCoordinates(latitude, longitude)

            val addressLine = geocodingResult?.street ?: geocodingResult?.fullAddress ?: "Lat: $latitude, Lon: $longitude"
            val city = geocodingResult?.city ?: "Unknown City"

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

            sendLocalizedMessage(MessageKey.ADDRESS_SAVED_DETAILS, chatId, address.addressLine, address.city)
            setState(chatId, BotState.REGISTERED)
            showMainMenu(chatId)
        } catch (e: Exception) {
            logger.error("Failed to save address after geocoding: ${e.message}", e)
            sendLocalizedMessage(MessageKey.ERROR_ADDRESS_SAVE_FAILED, chatId)
            setState(chatId, BotState.REGISTERED)
            showMainMenu(chatId)
        }
    }

    private fun showMainMenu(chatId: Long) {
        setState(chatId, BotState.REGISTERED)
        setMenuState(chatId, MenuStates.MAIN_MENU)

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
                    add(KeyboardButton(localizedMessageService.getMessage(MessageKey.BUTTON_MY_ORDERS, chatId)))
                    add(KeyboardButton(localizedMessageService.getMessage(MessageKey.BUTTON_MY_ADDRESSES, chatId)))
                },
                KeyboardRow().apply {
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
        setTemporaryData(chatId, "current_category_id", categoryId.toString())

        val markup = ReplyKeyboardMarkup()
        val keyboard = ArrayList<KeyboardRow>()
        val allProducts = productService.getProductsByCategoryId(categoryId)
        val locale = getUserLocale(chatId)
        var currentRow = KeyboardRow()

        allProducts.forEachIndexed { index, product ->
            val buttonText = when(locale) {
                "uz" -> product.nameUz
                "ru" -> product.nameRu
                else -> product.name
            }

            if (buttonText.isNotBlank()){
                currentRow.add(KeyboardButton(buttonText))
            }

            if (index % 2 == 1 || index == allProducts.size - 1){
                keyboard.add(currentRow)
                currentRow = KeyboardRow()
            }
        }

        val backRow = KeyboardRow()
        backRow.add(KeyboardButton(localizedMessageService.getMessage(MessageKey.BUTTON_RETURN, chatId)))
        keyboard.add(backRow)

        markup.keyboard = keyboard
        markup.resizeKeyboard = true

        sendMessage(chatId, SendMessage().apply {
            text = localizedMessageService.getMessage(MessageKey.PRODUCT_CHOOSE, chatId)
            replyMarkup = markup
        })
    }

    private fun showProductDetails(chatId: Long, productId: Long, callbackQueryId: String? = null) {
        try {
            // If we have a callback query ID, show loading notification
            callbackQueryId?.let {
                answerCallbackQuery(it, localizedMessageService.getMessage(MessageKey.LOADING, chatId))
            }

            // 1) fetch & guard
            val product = productService.getProductById(productId)
            if (product == null) {
                sendLocalizedMessage(MessageKey.ERROR_PRODUCT_NOT_FOUND, chatId)
                // go back to list
                getTemporaryData(chatId, "current_category_id")?.toLongOrNull()?.let {
                    setMenuState(chatId, MenuStates.PRODUCT_VIEW)
                    showProductMenu(chatId, it)
                } ?: run {
                    setMenuState(chatId, MenuStates.CATEGORY_VIEW)
                    showCategoriesMenu(chatId)
                }
                return
            }

            val categoryId = getTemporaryData(chatId, "current_category_id")?.toLongOrNull() ?: 0L

            // Store current product and initial quantity
            setTemporaryData(chatId, "current_product_id", productId.toString())
            setTemporaryData(chatId, "current_quantity", "1")

            // Build product info
            val currentQty = 1
            val title = getLocalizedProductName(product, chatId)
            val description = product.description?.takeIf { it.isNotBlank() }?.let { "\n\n$it" } ?: ""
            val text = buildString {
                append(title)
                append("\n")
                append(localizedMessageService.getMessage(MessageKey.PRICE, chatId))
                append(": ")
                append("${product.price.times(currentQty.toBigDecimal())}")
                append(" ")
                append(product.currency)
                append(description)
            }

            // Create inline keyboard for quantity
            val inlineKb = InlineKeyboardMarkup().apply {
                keyboard = listOf(
                    listOf(
                        InlineKeyboardButton("➖").apply {
                            callbackData = "decrease_quantity:$productId:$currentQty:$categoryId"
                        },
                        InlineKeyboardButton(currentQty.toString()).apply {
                            callbackData = "quantity_info"
                        },
                        InlineKeyboardButton("➕").apply {
                            callbackData = "increase_quantity:$productId:$currentQty:$categoryId"
                        }
                    ),
                    listOf(
                        InlineKeyboardButton(localizedMessageService.getMessage(MessageKey.BUTTON_ADD_TO_CART, chatId)).apply {
                            callbackData = "add_to_cart:$productId:$currentQty"
                        }
                    )
                )
            }

            // First send main message with inline keyboard but WITHOUT reply keyboard
            var productMessageId: Int? = null
            val hasPhoto = product.image != null &&
                    (product.image.startsWith("http://") || product.image.startsWith("https://"))

            if (hasPhoto) {
                try {
                    val photoMsg = execute(SendPhoto().apply {
                        setChatId(chatId)
                        photo = InputFile(product.image)
                        caption = text
                        replyMarkup = inlineKb
                    })
                    productMessageId = photoMsg.messageId
                    setTemporaryData(chatId, "product_message_id", productMessageId.toString())
                    setTemporaryData(chatId, "product_has_photo", "true")
                } catch (e: Exception) {
                    logger.error("Failed to send product image, falling back to text: ${e.message}", e)
                    // Fallback to regular text message if image fails
                    val textMsg = execute(SendMessage().apply {
                        this.chatId = chatId.toString()
                        this.text = text
                        this.replyMarkup = inlineKb
                    })
                    productMessageId = textMsg.messageId
                    setTemporaryData(chatId, "product_message_id", productMessageId.toString())
                    setTemporaryData(chatId, "product_has_photo", "false")
                }
            } else {
                // No valid image, send text only
                val textMsg = execute(SendMessage().apply {
                    this.chatId = chatId.toString()
                    this.text = text
                    this.replyMarkup = inlineKb
                })
                productMessageId = textMsg.messageId
                setTemporaryData(chatId, "product_message_id", productMessageId.toString())
                setTemporaryData(chatId, "product_has_photo", "false")
            }

            // Update the reply keyboard separately with a visible message
            val actionKb = ReplyKeyboardMarkup().apply {
                keyboard = listOf(
                    KeyboardRow(listOf(
                        KeyboardButton(localizedMessageService.getMessage(MessageKey.BUTTON_ADD_TO_CART, chatId)),
                        KeyboardButton(localizedMessageService.getMessage(MessageKey.BUTTON_RETURN, chatId))
                    ))
                )
                resizeKeyboard = true
                oneTimeKeyboard = false
            }

            // Send a message with actual text to update the keyboard
            val actionMsg = execute(SendMessage().apply {
                this.chatId = chatId.toString()
                this.text = localizedMessageService.getMessage(MessageKey.PRODUCT_ACTION_PROMPT, chatId)
                this.replyMarkup = actionKb
            })

            setTemporaryData(chatId, "action_message_id", actionMsg.messageId.toString())

            // Update state
            setMenuState(chatId, MenuStates.QUANTITY_SELECTOR)
        } catch (e: Exception) {
            logger.error("Error in showProductDetails: ${e.message}", e)
            sendLocalizedMessage(MessageKey.ERROR_GENERIC, chatId)
        }
    }

    private fun handleQuantityAdjustment(callbackQuery: CallbackQuery) {
        val chatId = callbackQuery.message?.chatId ?: return
        val messageId = callbackQuery.message?.messageId ?: return
        val data = callbackQuery.data ?: return
        val parts = data.split(":")
        val action = parts[0]
        val productId = parts[1].toLong()
        var currentQuantity = parts[2].toInt()
        val categoryId = parts.getOrNull(3)?.toLong() ?: getTemporaryData(chatId, "current_category_id")?.toLongOrNull() ?: 0L

        when (action) {
            "increase_quantity" -> currentQuantity++
            "decrease_quantity" -> {
                if (currentQuantity > 1) currentQuantity--
                else {
                    answerCallbackQuery(callbackQuery.id, localizedMessageService.getMessage(MessageKey.INVALID_QUANTITY, chatId))
                    return
                }
            }
            "add_to_cart" -> {
                try {
                    val quantityToAdd = parts[2].toInt()
                    // Pass both messageId and callbackQueryId to handleAddToCartAndGoToCheckout
                    handleAddToCartAndGoToCheckout(chatId, productId, quantityToAdd, messageId, callbackQuery.id)
                    return
                } catch (e: Exception) {
                    logger.error("Error adding item to cart via inline button: ${e.message}", e)
                    answerCallbackQuery(callbackQuery.id, localizedMessageService.getMessage(MessageKey.ERROR_CART_ADD_FAILED, chatId))
                    return
                }
            }
        }

        if (action == "increase_quantity" || action == "decrease_quantity") {
            setTemporaryData(chatId, "current_quantity", currentQuantity.toString())

            val prod = productService.getProductById(productId) ?: return
            val title = getLocalizedProductName(prod, chatId)
            val text = "$title\n${localizedMessageService.getMessage(MessageKey.PRICE, chatId)}: ${prod.price} ${prod.currency}"

            val addToCartText = localizedMessageService.getMessage(MessageKey.BUTTON_ADD_TO_CART, chatId)

            val inline = InlineKeyboardMarkup().apply {
                keyboard = listOf(
                    listOf(
                        InlineKeyboardButton().apply {
                            this.text = "➖"
                            callbackData = "decrease_quantity:$productId:$currentQuantity:$categoryId"
                        },
                        InlineKeyboardButton().apply {
                            this.text = currentQuantity.toString()
                            callbackData = "quantity_info"
                        },
                        InlineKeyboardButton().apply {
                            this.text = "➕"
                            callbackData = "increase_quantity:$productId:$currentQuantity:$categoryId"
                        }
                    ),
                    listOf(
                        InlineKeyboardButton().apply {
                            this.text = addToCartText
                            callbackData = "add_to_cart:$productId:$currentQuantity"
                        }
                    )
                )
            }

            try {
                // Check if the message has a photo (meaning it's a photo message)
                val hasPhoto = callbackQuery.message?.photo != null ||
                        (callbackQuery.message?.caption != null && callbackQuery.message?.text == null)

                if (hasPhoto) {
                    // For photo messages, update the caption
                    execute(EditMessageCaption().apply {
                        setChatId(chatId.toString())
                        setMessageId(messageId)
                        caption = text
                        replyMarkup = inline
                    })
                } else {
                    // For text messages, update the text
                    execute(EditMessageText().apply {
                        setChatId(chatId.toString())
                        setMessageId(messageId)
                        setText(text)
                        replyMarkup = inline
                    })
                }
                answerCallbackQuery(callbackQuery.id)
            } catch (e: TelegramApiException) {
                logger.error("Failed to edit message for quantity adjustment: ${e.message}")
                answerCallbackQuery(callbackQuery.id, localizedMessageService.getMessage(MessageKey.ERROR_GENERIC, chatId))
            }
            return
        }
    }


    private fun answerCallbackQuery(callbackQueryId: String, text: String? = null) {
        val answer = AnswerCallbackQuery().apply {
            setCallbackQueryId(callbackQueryId)
            setText(text)
        }
        try {
            execute(answer)
        } catch (e: TelegramApiException) {
            logger.warn("Failed to answer callback query $callbackQueryId: ${e.message}")
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

            keyboardRows.add(listOf(
                InlineKeyboardButton().apply {
                    text = "🗑 ${localizedMessageService.getMessage(MessageKey.CLEAR_CART, chatId)}"
                    callbackData = "clear_cart"
                }
            ))
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

            // Update menu state first
            setMenuState(chatId, MenuStates.ADDRESS_SELECTION)

            // Send a single message with both inline keyboard and updated reply keyboard
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

            val lastViewedProductId = getTemporaryData(chatId, "current_product_id")
            if (lastViewedProductId != null) {
                keyboardRows.add(listOf(
                    InlineKeyboardButton().apply {
                        text = "🔙 ${localizedMessageService.getMessage(MessageKey.BUTTON_RETURN_TO_PRODUCT, chatId)}"
                        callbackData = "back_to_product"
                    }
                ))
            }

            markup.keyboard = keyboardRows
            message.replyMarkup = markup

            // Send address selection message with inline keyboard
            val addressMsg = execute(message)
            setTemporaryData(chatId, "address_message_id", addressMsg.messageId.toString())

            // Update reply keyboard to only show return button in a separate message
            val returnKeyboard = ReplyKeyboardMarkup().apply {
                keyboard = listOf(
                    KeyboardRow(listOf(
                        KeyboardButton(localizedMessageService.getMessage(MessageKey.BUTTON_RETURN, chatId))
                    ))
                )
                resizeKeyboard = true
            }

            execute(SendMessage().apply {
                this.chatId = chatId.toString()
                this.text = "⌨\uFE0F"  // Zero-width space - won't be visible to user
                this.replyMarkup = returnKeyboard
            })

            setState(chatId, BotState.AWAITING_ADDRESS_SELECTION)
        } catch (e: Exception) {
            logger.error("Error during checkout: ${e.message}", e)
            sendLocalizedMessage(MessageKey.ERROR_CHECKOUT, chatId)
        }
    }

    private fun handleAddToCartAndGoToCheckout(chatId: Long, productId: Long, quantity: Int, messageId: Int? = null, callbackQueryId: String? = null) {
        try {
            // 1. Add item to cart
            cartService.addItemToCart(chatId, productId, quantity)
            val product = productService.getProductById(productId)
            val productName = if (product != null) getLocalizedProductName(product, chatId) else "Product"

            val successMessage = localizedMessageService.getMessage(MessageKey.ADDED_TO_CART, chatId, quantity, productName)

            // 2. Show notification via callback query if available
            callbackQueryId?.let {
                answerCallbackQuery(it, "${successMessage}\n${localizedMessageService.getMessage(MessageKey.REDIRECTING_TO_CHECKOUT, chatId)}")
            }

            // 3. Get stored message IDs
            val storedProductMsgId = getTemporaryData(chatId, "product_message_id")?.toIntOrNull()
            val storedActionMsgId = getTemporaryData(chatId, "action_message_id")?.toIntOrNull()

            // Determine the product message ID to delete
            val productMessageIdToDelete = messageId ?: storedProductMsgId

            // 4. Delete the product message (photo or text)
            if (productMessageIdToDelete != null) {
                try {
                    execute(DeleteMessage(chatId.toString(), productMessageIdToDelete))
                } catch (e: Exception) {
                    logger.warn("Failed to delete product message (ID: $productMessageIdToDelete): ${e.message}")
                    // Continue even if deletion fails
                }
            }

            // 5. Delete the action prompt message (reply keyboard message) if it exists and is different
            if (storedActionMsgId != null && storedActionMsgId != productMessageIdToDelete) {
                try {
                    execute(DeleteMessage(chatId.toString(), storedActionMsgId))
                } catch (e: Exception) {
                    logger.warn("Failed to delete action message (ID: $storedActionMsgId): ${e.message}")
                    // Continue even if deletion fails
                }
            }

            // 6. Clear temporary message IDs from state
            stateManager.clearTemporaryData(chatId, "product_message_id")
            stateManager.clearTemporaryData(chatId, "action_message_id")
            stateManager.clearTemporaryData(chatId, "product_has_photo")


            // 7. Proceed to checkout - this will now send fresh messages for address selection
            handleCheckout(chatId)

        } catch (e: Exception) {
            logger.error("Error adding to cart and checking out: ${e.message}", e)

            // Handle error differently based on how we were called
            callbackQueryId?.let {
                answerCallbackQuery(it, localizedMessageService.getMessage(MessageKey.ERROR_CART_ADD_FAILED, chatId))
            } ?: run {
                sendLocalizedMessage(MessageKey.ERROR_CART_ADD_FAILED, chatId)
            }
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
        val currentMenuState = getCurrentMenuState(chatId)

        stateManager.clearTemporaryData(chatId, "current_quantity")

        when (currentMenuState) {
            MenuStates.QUANTITY_SELECTOR -> {
                val categoryIdStr = getTemporaryData(chatId, "current_category_id")
                if (categoryIdStr != null) {
                    val categoryId = categoryIdStr.toLong()
                    setMenuState(chatId, MenuStates.PRODUCT_VIEW)
                    setState(chatId, BotState.REGISTERED)
                    showProductMenu(chatId, categoryId)
                } else {
                    logger.warn("Category ID not found when returning from QUANTITY_SELECTOR for chat $chatId. Returning to categories.")
                    setMenuState(chatId, MenuStates.CATEGORY_VIEW)
                    setState(chatId, BotState.REGISTERED)
                    showCategoriesMenu(chatId)
                }
                // Don't clear current_product_id until after using it
                stateManager.clearTemporaryData(chatId, "current_product_id")
            }
            MenuStates.PRODUCT_VIEW -> {
                setMenuState(chatId, MenuStates.CATEGORY_VIEW)
                setState(chatId, BotState.REGISTERED)
                showCategoriesMenu(chatId)
                stateManager.clearTemporaryData(chatId, "current_category_id")
            }
            MenuStates.ADDRESS_SELECTION -> {
                // Return from address selection to product details
                val productIdStr = getTemporaryData(chatId, "current_product_id")
                if (productIdStr != null) {
                    val productId = productIdStr.toLong()
                    setMenuState(chatId, MenuStates.QUANTITY_SELECTOR)
                    setState(chatId, BotState.REGISTERED)
                    showProductDetails(chatId, productId)
                } else {
                    // Fallback to categories if product ID is missing
                    setMenuState(chatId, MenuStates.CATEGORY_VIEW)
                    setState(chatId, BotState.REGISTERED)
                    showCategoriesMenu(chatId)
                }
            }
            MenuStates.CATEGORY_VIEW -> {
                setMenuState(chatId, MenuStates.MAIN_MENU)
                setState(chatId, BotState.REGISTERED)
                showMainMenu(chatId)
            }
            MenuStates.SETTINGS_VIEW, MenuStates.ADDRESSES_VIEW -> {
                setMenuState(chatId, MenuStates.MAIN_MENU)
                setState(chatId, BotState.REGISTERED)
                showMainMenu(chatId)
            }
            MenuStates.PRODUCT_DETAIL_VIEW -> {
                setMenuState(chatId, MenuStates.PRODUCT_VIEW)
                setState(chatId, BotState.REGISTERED)
                showMainMenu(chatId)
            }
            else -> {
                logger.warn("Unhandled return from menu state: $currentMenuState for chat $chatId. Defaulting to main menu.")
                setMenuState(chatId, MenuStates.MAIN_MENU)
                setState(chatId, BotState.REGISTERED)
                showMainMenu(chatId)
                stateManager.clearTemporaryData(chatId)
            }
        }
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

    private fun processOrderWithAddress(chatId: Long, addressId: Long) {
        orderManager.setAddressId(chatId, addressId)
        proceedToOrderConfirmation(chatId)
    }

    private fun showOrderConfirmation(chatId: Long, cart: CartDTO) {
        val messageBuilder = StringBuilder(localizedMessageService.getMessage(MessageKey.ORDERS_TITLE, chatId))

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
            append("\n${localizedMessageService.getMessage(MessageKey.SUBTOTAL, chatId)}: $subtotal UZS")
            append("\n${localizedMessageService.getMessage(MessageKey.SERVICE_FEE, chatId)}: $serviceCharge UZS")
            append("\n${localizedMessageService.getMessage(MessageKey.DELIVERY_FEE, chatId)}: $deliveryFee UZS")
            append("\n${localizedMessageService.getMessage(MessageKey.DISCOUNT, chatId)}: -$discount UZS")
            append("\n\n${localizedMessageService.getMessage(MessageKey.CART_TOTAL, chatId)}: $total UZS")
            append("\n\n${localizedMessageService.getMessage(MessageKey.PAYMENT_METHOD, chatId)}: ${localizedMessageService.getMessage(MessageKey.CASH_ON_DELIVERY, chatId)}")
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
        val messageId = callbackQuery.message?.messageId ?: return

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
                orderService.createOrder(user.id!!, orderRequest)
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
                replyMarkup = null
            })
        }
    }

    private fun showUserOrders(chatId: Long) {
        try {
            val user = getCurrentUser(chatId) ?: run {
                sendLocalizedMessage(MessageKey.ERROR_AUTH_REQUIRED, chatId)
                return
            }

            val pageable = PageRequest.of(0, 10)
            val orders = orderService.getCustomerOrders(user.id!!, pageable)

            if (orders.isEmpty) {
                sendLocalizedMessage(MessageKey.ORDERS_EMPTY, chatId)
                return
            }

            val messageBuilder = StringBuilder(localizedMessageService.getMessage(MessageKey.ORDERS_TITLE, chatId))
            messageBuilder.append("\n\n")

            val markup = InlineKeyboardMarkup()
            val keyboardRows = mutableListOf<List<InlineKeyboardButton>>()

            orders.content.forEachIndexed { index, order ->
                messageBuilder.apply {
                    append("📋 *${localizedMessageService.getMessage(MessageKey.ORDER, chatId)} #${order.id}*\n")
                    append("📅 ${order.orderDate}\n")
                    append("💰 ${order.totalAmount} UZS\n")
                    append("🚚 ${localizedStatusName(order.status, chatId)}\n\n")
                }

                val orderButton = InlineKeyboardButton().apply {
                    text = "🔍 ${localizedMessageService.getMessage(MessageKey.VIEW_ORDER_DETAILS, chatId)} #${order.id}"
                    callbackData = "view_order:${order.id}"
                }
                keyboardRows.add(listOf(orderButton))
            }

            keyboardRows.add(listOf(
                InlineKeyboardButton().apply {
                    text = "🏠 ${localizedMessageService.getMessage(MessageKey.BUTTON_MAIN_MENU, chatId)}"
                    callbackData = "main_menu"
                }
            ))

            markup.keyboard = keyboardRows

            sendMessage(chatId, SendMessage().apply {
                text = messageBuilder.toString()
                enableMarkdown(true)
                replyMarkup = markup
            })

        } catch (e: Exception) {
            logger.error("Error showing user orders: ${e.message}", e)
            sendLocalizedMessage(MessageKey.ERROR_ORDERS_FETCH_FAILED, chatId)
        }
    }

    private fun localizedStatusName(status: OrderStatus, chatId: Long): String {
        val key = when (status) {
            OrderStatus.PENDING -> MessageKey.STATUS_PENDING
            OrderStatus.IN_PROGRESS -> MessageKey.STATUS_IN_PROGRESS
            OrderStatus.ACCEPTED -> MessageKey.STATUS_ACCEPTED
            OrderStatus.COMPLETED -> MessageKey.STATUS_COMPLETED
            OrderStatus.CANCELLED -> MessageKey.STATUS_CANCELLED
            OrderStatus.REJECTED -> MessageKey.STATUS_REJECTED
            OrderStatus.REFUNDED -> MessageKey.STATUS_REFUNDED
            else -> MessageKey.STATUS_UNKNOWN
        }

        return localizedMessageService.getMessage(key, chatId)
    }

    private fun showOrderDetails(chatId: Long, orderId: Long, messageId: Int) {
        try {
            val order = orderService.getOrderById(orderId)
            val addressInfo = getAddressDetails(order.addressId, chatId)

            val messageBuilder = StringBuilder("*${localizedMessageService.getMessage(MessageKey.ORDER, chatId)} #${order.id}*\n\n")
            messageBuilder.apply {
                append("📅 ${order.orderDate}\n")
                append("🚚 ${localizedStatusName(order.status, chatId)}\n")
                append("💳 ${order.paymentOption}\n")
                append("📍 $addressInfo\n\n")
            }

            messageBuilder.append("*${localizedMessageService.getMessage(MessageKey.ORDER_ITEMS, chatId)}:*\n")
            order.orderItems.forEach { item ->
                val product = productService.getProductById(item.productId!!)
                val productName = getLocalizedProductName(product!!, chatId)
                val itemTotal = item.price.multiply(BigDecimal(item.quantity))
                messageBuilder.append("• $productName x ${item.quantity} = $itemTotal UZS\n")
            }

            messageBuilder.append("\n*${localizedMessageService.getMessage(MessageKey.TOTAL, chatId)}: ${order.totalAmount} UZS*")

            val markup = InlineKeyboardMarkup()
            markup.keyboard = listOf(
                listOf(
                    InlineKeyboardButton().apply {
                        text = localizedMessageService.getMessage(MessageKey.BUTTON_BACK_TO_ORDERS, chatId)
                        callbackData = "show_orders"
                    }
                ),
                listOf(
                    InlineKeyboardButton().apply {
                        text = localizedMessageService.getMessage(MessageKey.BUTTON_MAIN_MENU, chatId)
                        callbackData = "main_menu"
                    }
                )
            )

            execute(EditMessageText().apply {
                setChatId(chatId.toString())
                setMessageId(messageId)
                text = messageBuilder.toString()
                enableMarkdown(true)
                replyMarkup = markup
            })

        } catch (e: Exception) {
            logger.error("Error showing order details: ${e.message}", e)
            execute(EditMessageText().apply {
                setChatId(chatId.toString())
                setMessageId(messageId)
                text = localizedMessageService.getMessage(MessageKey.ERROR_ORDER_DETAILS_FAILED, chatId)
                replyMarkup = null
            })
        }
    }

    private fun getAddressDetails(addressId: Long, chatId: Long): String {
        try {
            val user = userRepository.findByTelegramChatId(chatId)
            if (user == null) {
                logger.error("Cannot fetch address details: User not found for chat $chatId")
                return "Address user not found"
            }

            val address = addressRepository.findByIdAndDeletedFalse(addressId)
                ?: throw ResourceNotFoundException(addressId)

            if (address.user?.id != user.id) {
                logger.warn("User $chatId (ID: ${user.id}) attempted to access address $addressId owned by user ${address.user?.id}")
                throw ForbiddenException() // Or return a generic error string
            }

            return "${address.addressLine}, ${address.city}"

        } catch (e: ForbiddenException) {
            logger.error("Forbidden access attempt for address $addressId by chat $chatId: ${e.message}")
            return "Address access denied" // Or use localized message
        }
        catch (e: ResourceNotFoundException) {
            logger.error("Error fetching address details: Address $addressId not found. ${e.message}")
            return "Address not found" // Or use localized message
        }
        catch (e: Exception) {
            logger.error("Error fetching address details for address $addressId, chat $chatId: ${e.message}", e)
            return "Address not available" // Or use localized message
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
