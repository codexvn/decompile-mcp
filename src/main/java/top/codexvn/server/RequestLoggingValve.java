package top.codexvn.server;

import java.io.IOException;
import java.util.UUID;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 每次 HTTP 请求生成 traceId（MDC），记录请求方法、路径和来源 IP。
 */
public class RequestLoggingValve extends ValveBase {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingValve.class);
    public static final String MDC_KEY = "traceId";

    @Override
    public boolean isAsyncSupported() {
        return true;
    }

    @Override
    public void invoke(Request request, Response response) throws IOException {
        // 取请求中原有的 traceId，否则生成新的
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        response.setHeader("X-Trace-Id", traceId);
        MDC.put(MDC_KEY, traceId);

        try {
            long start = System.currentTimeMillis();
            log.info("{} {} | remote={} | session={}",
                request.getMethod(),
                request.getRequestURI(),
                request.getRemoteAddr(),
                request.getParameter("sessionId"));

            getNext().invoke(request, response);

            long elapsed = System.currentTimeMillis() - start;
            log.info("{} {} → {} {}ms",
                request.getMethod(), request.getRequestURI(),
                response.getStatus(), elapsed);

        } catch (Exception e) {
            log.error("{} {} failed: {}", request.getMethod(),
                request.getRequestURI(), e.getMessage());
            throw new IOException(e);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
