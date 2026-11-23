package com.writelab.document;

import org.springframework.http.HttpStatus;

/**
 * Stable, user-visible reasons a User Document upload or processing step can fail. The code is
 * persisted for {@code FAILED} documents and returned as the API error code.
 */
public enum DocumentErrorCode {

    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "文件超过单文件大小上限。"),
    DOCUMENT_LIMIT_REACHED(HttpStatus.PAYLOAD_TOO_LARGE, "用户文档数量已达上限。"),
    CHUNK_LIMIT_REACHED(HttpStatus.PAYLOAD_TOO_LARGE, "文档分块总数将超过上限。"),
    UNSUPPORTED_FILE_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "只支持 UTF-8 的 .md 或 .txt 文件。"),
    EMPTY_DOCUMENT(HttpStatus.UNPROCESSABLE_ENTITY, "文档内容为空或只有空白字符。"),
    INVALID_ENCODING(HttpStatus.UNPROCESSABLE_ENTITY, "文档不是有效的 UTF-8 文本。"),
    BINARY_DOCUMENT(HttpStatus.UNPROCESSABLE_ENTITY, "文档看起来是二进制内容，无法作为文本处理。"),
    DUPLICATE_DOCUMENT(HttpStatus.CONFLICT, "相同内容的文档已经存在。"),
    DOCUMENT_RETRY_UNAVAILABLE(HttpStatus.CONFLICT, "该文档缺少可重试的已解析内容，请删除后重新上传。"),
    INVALID_DOCUMENT_UPDATE(HttpStatus.BAD_REQUEST, "请求参数不合法。"),
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "找不到对应的用户文档。"),
    PROCESSING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "文档处理失败，请稍后重试。"),
    EMBEDDING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "文档向量生成失败，请稍后重试。");

    private final HttpStatus status;
    private final String message;

    DocumentErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
