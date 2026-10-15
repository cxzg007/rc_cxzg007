package com.rightcapital.notifications;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiAccessFilter extends OncePerRequestFilter {
    // JSON Unicode escapes can expand one body byte to six, plus headers and metadata.
    private static final int MAX_ENVELOPE = 8 * 1024 * 1024;
    private final NotificationProperties properties;
    public ApiAccessFilter(NotificationProperties properties) { this.properties = properties; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String key = request.getHeader("X-API-Key"); String client = null;
        if (key != null) for (var entry : properties.clients().entrySet()) {
            if (MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), entry.getValue().getBytes(StandardCharsets.UTF_8))) client = entry.getKey();
        }
        if (client == null) { error(response, 401, "unauthorized"); return; }
        request.setAttribute("clientId", client);
        byte[] bytes = request.getInputStream().readNBytes(MAX_ENVELOPE + 1);
        if (bytes.length > MAX_ENVELOPE) { error(response, 413, "request_too_large"); return; }
        var wrapped = new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] b, int offset, int len) { return input.read(b, offset, len); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous request body"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        };
        chain.doFilter(wrapped, response);
    }
    private void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}
