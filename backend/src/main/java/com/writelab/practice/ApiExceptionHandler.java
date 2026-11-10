package com.writelab.practice;

import com.writelab.dictionary.DictionaryEntryNotFoundException;
import com.writelab.feedback.InvalidFeedbackException;
import com.writelab.feedback.ModelCallException;
import com.writelab.feedback.ModelGenerationException;
import com.writelab.vocabulary.SavedVocabularyNotFoundException;
import com.writelab.vocabulary.VocabularyAlreadySavedException;
import com.writelab.vocabulary.VocabularySuggestionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.InterruptedIOException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(WordCountOutOfRangeException.class)
    ResponseEntity<ApiError> handleWordCountOutOfRange(WordCountOutOfRangeException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("WORD_COUNT_OUT_OF_RANGE", exception.getMessage()));
    }

    @ExceptionHandler(PracticeNotFoundException.class)
    ResponseEntity<ApiError> handlePracticeNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("PRACTICE_NOT_FOUND", "找不到对应的练习记录，请刷新后重试。"));
    }

    @ExceptionHandler(DictionaryEntryNotFoundException.class)
    ResponseEntity<ApiError> handleDictionaryEntryNotFound(DictionaryEntryNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(
                        "DICTIONARY_ENTRY_NOT_FOUND",
                        "词典中暂时没有“" + exception.word() + "”的条目。"
                ));
    }

    @ExceptionHandler(SavedVocabularyNotFoundException.class)
    ResponseEntity<ApiError> handleSavedVocabularyNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("SAVED_VOCABULARY_NOT_FOUND", "找不到对应的已保存词汇，请刷新后重试。"));
    }

    @ExceptionHandler(VocabularySuggestionNotFoundException.class)
    ResponseEntity<ApiError> handleVocabularySuggestionNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("VOCABULARY_SUGGESTION_NOT_FOUND", "找不到对应的词汇建议，请刷新后重试。"));
    }

    @ExceptionHandler(VocabularyAlreadySavedException.class)
    ResponseEntity<ApiError> handleVocabularyAlreadySaved() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("VOCABULARY_ALREADY_SAVED", "该词已在 Saved Vocabulary 中。"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("请求参数不合法。");
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST_BODY", "请求内容格式不正确。"));
    }

    @ExceptionHandler(InvalidFeedbackException.class)
    ResponseEntity<ApiError> handleInvalidFeedback(InvalidFeedbackException exception) {
        log.error("结构化反馈未通过校验: {}", exception.errors());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INVALID_MODEL_RESPONSE", "模型返回的反馈格式不符合要求，请稍后重试。"));
    }

    @ExceptionHandler(ModelGenerationException.class)
    ResponseEntity<ApiError> handleModelGeneration(ModelGenerationException exception) {
        log.warn("真实模型在重试后仍未通过校验: errors={}", exception.errors());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("MODEL_GENERATION_FAILED", "模型多次返回的内容不符合要求，请稍后重试。"));
    }

    @ExceptionHandler(ModelCallException.class)
    ResponseEntity<ApiError> handleModelCall(ModelCallException exception) {
        Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
        if (isTimeout(exception)) {
            log.warn("真实模型调用超时", cause);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(new ApiError("MODEL_TIMEOUT", "模型响应超时，请稍后重试。"));
        }
        log.warn("真实模型调用失败", cause);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("MODEL_CALL_FAILED", "模型服务暂时不可用，请稍后重试。"));
    }

    private static boolean isTimeout(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleMethodArgumentTypeMismatch() {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_PATH_PARAMETER", "请求路径参数格式不正确。"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError("METHOD_NOT_ALLOWED", "请求方法不受支持。"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNotFound(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", "请求的资源不存在。"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        log.error("未处理的服务异常", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "服务暂时不可用，请稍后重试。"));
    }
}
