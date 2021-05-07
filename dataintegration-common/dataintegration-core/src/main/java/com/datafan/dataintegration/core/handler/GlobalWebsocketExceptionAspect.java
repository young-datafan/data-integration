package com.datafan.dataintegration.core.handler;

import com.datafan.dataintegration.core.model.Result;
import com.datafan.dataintegration.core.StatusCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Component;

/**
 * 全局的websocket aop异常处理
 *
 * @author gavin
 * @create 2020/3/3 5:03 下午
 */
@ConditionalOnClass(MessageMapping.class)
@Aspect
@Component
public class GlobalWebsocketExceptionAspect {
    private static final Logger log = LoggerFactory.getLogger(GlobalWebsocketExceptionAspect.class);

    @Around("@annotation(org.springframework.messaging.handler.annotation.MessageMapping)")
    public Object websocketExceptionPoint(ProceedingJoinPoint pjp) {
        return exceptionHandler(pjp);
    }

    /**
     * 异常处理
     *
     * @param pjp ProceedingJoinPoint
     * @return Result
     */
    private Object exceptionHandler(ProceedingJoinPoint pjp) {
        try {
            return pjp.proceed();
        } catch (Throwable throwable) {
            log.error("websocket请求异常", throwable);
            try {
                return GlobalExceptionHandler.buildResult(throwable);
            } catch (Throwable e) {
                return Result.fail(StatusCode.CODE_10010, null, "业务请求失败");
            }
        }
    }

}
