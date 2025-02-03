package com.example.demo

import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

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

class DuplicateResourceException : RestoranProException() {
    override fun errorCode() = ErrorCode.DUPLICATE_RESOURCE
}

class ForbiddenException : RestoranProException() {
    override fun errorCode() = ErrorCode.FORBIDDEN
}

open class InvalidInputException : RestoranProException() {
    override fun errorCode() = ErrorCode.INVALID_INPUT
}

class ResourceNotFoundException : RestoranProException() {
    override fun errorCode() = ErrorCode.RESOURCE_NOT_FOUND
}

class ValidationException : RestoranProException() {
    override fun errorCode() = ErrorCode.VALIDATION_ERROR
}

class UserNotFoundException(
    private val messageSource: MessageSource
) : RestoranProException() {
    override fun errorCode() = ErrorCode.USER_NOT_FOUND

}

class InvalidPhoneNumber() : InvalidInputException() {
}

class TooManyAttempts(
    private val messageSource: MessageSource
) : RestoranProException() {
    override fun errorCode() = ErrorCode.GENERAL_ERROR
}


@ControllerAdvice
class GlobalExceptionHandler (
    private  val errorMessageSource: ResourceBundleMessageSource,
    private val messageSource: MessageSource
) : ResponseEntityExceptionHandler() {

    @ExceptionHandler(Throwable::class)
    fun handleException(ex: Exception): ResponseEntity<BaseMessage> {
       return  when(ex){
            is RestoranProException ->{
                val message = ex.getErrorMessage(errorMessageSource)
                ResponseEntity.badRequest().body(message)
            }

           else -> {
               ex.printStackTrace()
               ResponseEntity.badRequest().body(BaseMessage(
                   code = -1,
                   message = "Xatolik sodir bo'ldi, ${ex.message}"
               ))
           }

        }
    }

    @ExceptionHandler(ResourceNotFoundException::class)
    fun handleResourceNotFound(
        ex: ResourceNotFoundException,
        request: WebRequest
    ): ResponseEntity<BaseMessage> {
        val message = messageSource.getMessage(
            ex.errorCode().name,
            ex.getErrorMessageArguments(),
            LocaleContextHolder.getLocale()
        )

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(BaseMessage(
                code = ex.errorCode().code,
                message = message
            ))
    }

    @ExceptionHandler(InvalidInputException::class)
    fun handleInvalidInput(
        ex: InvalidInputException,
        request: WebRequest
    ): ResponseEntity<BaseMessage> {
        val message = messageSource.getMessage(
            ex.errorCode().name,
            ex.getErrorMessageArguments(),
            LocaleContextHolder.getLocale()
        )

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(BaseMessage(
                code = ex.errorCode().code,
                message = message
            ))
    }
}
