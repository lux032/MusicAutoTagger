package org.example.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.util.I18nUtil;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 国际化资源接口 - 为前端提供多语言文本
 */
@Slf4j
public class I18nServlet extends HttpServlet {

    private final Gson gson;

    public I18nServlet() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");

        try {
            // 获取所有国际化消息
            Properties messages = I18nUtil.getAllMessages();

            // 转换为Map
            Map<String, String> messageMap = new HashMap<>();
            for (String key : messages.stringPropertyNames()) {
                messageMap.put(key, messages.getProperty(key));
            }

            // 添加当前语言信息
            Map<String, Object> data = new HashMap<>();
            data.put("language", I18nUtil.getCurrentLanguage());
            data.put("messages", messageMap);

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(gson.toJson(data));

        } catch (Exception e) {
            log.error("获取国际化资源失败", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            resp.getWriter().write(gson.toJson(error));
        }
    }
}
