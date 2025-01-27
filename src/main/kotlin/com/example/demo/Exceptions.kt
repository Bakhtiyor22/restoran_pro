package com.example.demo

import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import java.util.*

sealed class RestoranProException : RuntimeException() {

    abstract fun errorCode(): ErrorCode

    open fun getErrorMessageArguments(): Array<Any?>? = null

    fun getErrorMessage(errorMessageSource: ResourceBundleMessageSource): BaseMessage {
        val errorMessage = try {
            errorMessageSource.getMessage(
                errorCode().name,
                getErrorMessageArguments(),
                LocaleContextHolder.getLocale()
            )
        } catch (e: Exception) {
            e.message
        }
        return BaseMessage(errorCode().code, errorMessage)
    }
}


class  UserNotFoundException : RestoranProException() {
    override fun errorCode() = ErrorCode.USER_NOT_FOUND

}