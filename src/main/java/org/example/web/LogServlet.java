package org.example.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.service.LogCollector;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志接口 - 提供实时日志查看
 */
@Slf4j
public class LogServlet extends HttpServlet {
    
    private final Gson gson;
    
    public LogServlet() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        
        try {
            // 获取请求参数
            String limitParam = req.getParameter("limit");
            int limit = 50; // 默认获取最近50条
            
            if (limitParam != null) {
                try {
                    limit = Integer.parseInt(limitParam);
                    limit = Math.min(limit, 200); // 最多200条
                } catch (NumberFormatException e) {
                    // 使用默认值
                }
            }
            
            // 获取日志
            List<LogCollector.LogEntry> logs = LogCollector.getRecentLogs(limit);
            
            Map<String, Object> data = new HashMap<>();
            data.put("logs", logs);
            data.put("count", logs.size());
            
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(gson.toJson(data));
            
        } catch (Exception e) {
            log.error("获取日志失败", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            resp.getWriter().write(gson.toJson(error));
        }
    }
}