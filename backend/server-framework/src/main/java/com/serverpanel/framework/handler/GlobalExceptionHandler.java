package com.serverpanel.framework.handler;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationEventPublisher;
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

import com.serverpanel.common.audit.AccessDeniedEvent;
import com.serverpanel.common.core.R;
import com.serverpanel.common.exception.ErrorCode;
import com.serverpanel.common.exception.ServiceException;
import com.serverpanel.framework.security.LoginHelper;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.NotSafeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 全局异常处理：统一转换为 R 响应（HTTP 状态保持 200，业务码承载语义）。
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ApplicationEventPublisher eventPublisher;

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

    /**
     * 无权限（越权尝试）。
     *
     * <p>注意执行顺序：@SaCheckPermission 由 SaInterceptor 在 HandlerInterceptor#preHandle
     * 阶段校验，早于 Controller 方法上的 @Audit 切面 —— 被拒请求根本不会进入切面，
     * 所以越权尝试只能在这里单独留痕。把 @Audit 前移是错的，那会把「业务方法从未执行」
     * 记成「已执行」。这里只发布事件，落库交给 server-system（framework 不反向依赖 system）。
     */
    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public R<Void> handleNoPerm(Exception e, HttpServletRequest request) {
        publishAccessDenied(e, request);
        return R.fail(ErrorCode.FORBIDDEN);
    }

    /**
     * 高危操作未完成二级认证（@Audit(safe = true) 的 step-up 闸门）。
     *
     * <p>刻意用独立业务码 1010，而不是复用 403：前端 request.ts 捕获 1010 会弹出密码框
     * 完成二级认证后自动重放原请求；若复用 403，就与「真的没有权限」无法区分，
     * 前端只能对所有 403 弹密码框，语义是错的。
     */
    @ExceptionHandler(NotSafeException.class)
    public R<Void> handleNotSafe(NotSafeException e) {
        return R.fail(ErrorCode.AUTH_SAFE_REQUIRED);
    }

    /** 参数校验失败：@Valid 请求体 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        return R.fail(ErrorCode.BAD_REQUEST.getCode(), msg);
    }

    /** 参数校验失败：表单绑定 */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBind(BindException e) {
        String msg = e.getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数绑定失败");
        return R.fail(ErrorCode.BAD_REQUEST.getCode(), msg);
    }

    /**
     * 缺少必填参数 / 类型不匹配 / 请求体无法反序列化。
     *
     * <p>三者的响应体都是同一个 400，但根因完全不同（缺少 @RequestParam、路径变量
     * 类型不对、JSON 字段类型对不上导致 HttpMessageNotReadable），只看响应体永远
     * 不知道是哪一种。这里打一条 warn 落日志，避免每次 400 都靠猜。日志只带异常
     * 消息，不打印请求体（请求体可能含口令等敏感字段）。
     */
    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    public R<Void> handleBadRequest(Exception e, HttpServletRequest request) {
        log.warn("请求参数错误 [{} {}] {}", request.getMethod(), request.getRequestURI(), e.getMessage());
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

    /**
     * 发布越权事件（不携带请求体：越权请求的入参不应落库）。
     *
     * <p>任何异常都不得改变鉴权结果 —— 审计是旁路，403 照常返回。
     */
    private void publishAccessDenied(Exception e, HttpServletRequest request) {
        try {
            String deniedBy;
            if (e instanceof NotPermissionException npe) {
                deniedBy = "缺少权限：" + npe.getPermission();
            } else {
                deniedBy = "缺少角色：" + ((NotRoleException) e).getRole();
            }
            String operator = null;
            try {
                operator = LoginHelper.getUsername();
            } catch (Exception ignored) {
                // 越权请求理论上已登录（未登录会先被 checkLogin 拦成 401），此处仅兜底
            }
            eventPublisher.publishEvent(new AccessDeniedEvent(operator,
                request.getRequestURI(), request.getMethod(), deniedBy,
                clientIp(request), request.getHeader("User-Agent")));
        } catch (Exception ignored) {
            // 留痕失败不影响 403 的返回
        }
    }

    /** 提取客户端 IP（优先 X-Forwarded-For，与登录日志口径一致） */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
