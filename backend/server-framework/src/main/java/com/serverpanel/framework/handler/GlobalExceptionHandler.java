package com.serverpanel.framework.handler;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.NotSafeException;
import com.serverpanel.common.core.R;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：统一转换为 R 响应（HTTP 状态保持 200，业务码承载语义）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常 */
    @ExceptionHandler(ServiceException.class)
    public R<Void> handleService(ServiceException e) {
        return R.fail(e.getCode(), e.getMessage());
    }

    /** 未登录 / token 失效 */
    @ExceptionHandler(NotLoginException.class)
    public R<Void> handleNotLogin(NotLoginException e) {
        return R.fail(ErrorCode.UNAUTHORIZED);
    }

    /** 无权限 */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public R<Void> handleNoPerm(Exception e) {
        return R.fail(ErrorCode.FORBIDDEN);
    }

    /** 高危操作未完成二级认证 */
    @ExceptionHandler(NotSafeException.class)
    public R<Void> handleNotSafe(NotSafeException e) {
        return R.fail(ErrorCode.FORBIDDEN.getCode(), "敏感操作，请先完成二级认证");
    }

    /** 参数校验失败：@Valid 请求体 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .findFirst().map(FieldError::getDefaultMessage).orElse("参数校验失败");
        return R.fail(ErrorCode.BAD_REQUEST.getCode(), msg);
    }

    /** 参数校验失败：表单绑定 */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBind(BindException e) {
        String msg = e.getFieldErrors().stream()
            .findFirst().map(FieldError::getDefaultMessage).orElse("参数绑定失败");
        return R.fail(ErrorCode.BAD_REQUEST.getCode(), msg);
    }

    /** 缺少必填参数 / 类型不匹配 */
    @ExceptionHandler({MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public R<Void> handleBadRequest(Exception e) {
        return R.fail(ErrorCode.BAD_REQUEST);
    }

    /** 上传超限 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleUpload(MaxUploadSizeExceededException e) {
        return R.fail(ErrorCode.PAYLOAD_TOO_LARGE);
    }

    /** 请求方式不支持 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public R<Void> handleMethod(HttpRequestMethodNotSupportedException e) {
        return R.fail(ErrorCode.METHOD_NOT_ALLOWED);
    }

    /** 静态资源/路径不存在 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleNoResource(NoResourceFoundException e) {
        return R.fail(ErrorCode.NOT_FOUND);
    }

    /** 兜底：未知异常 */
    @ExceptionHandler(Exception.class)
    public R<Void> handleUnknown(Exception e, HttpServletRequest request) {
        log.error("未处理异常 [{}] {}", request.getRequestURI(), e.getMessage(), e);
        return R.fail(ErrorCode.ERROR);
    }
}
