package byd.cxkcxkckx.sayfuck;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.lang.reflect.Type;
import java.net.URL;  // 添加URL导入
import java.net.HttpURLConnection;  // 添加HttpURLConnection导入
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class OpenAiHandler {
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String messageTemplate;
    private final Sayfuck plugin;
    private final List<Message> messageHistory;
    private final int maxHistory;  // 替换原来的静态MAX_HISTORY
    private final File historyFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public OpenAiHandler(Sayfuck plugin, String apiKey, String baseUrl, String model, String messageTemplate) {
        this.plugin = plugin;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.messageTemplate = messageTemplate;
        this.messageHistory = new ArrayList<>();
        this.maxHistory = plugin.getConfig().getInt("chat.max-history", 40);
        plugin.debug("设置最大历史记录数量: " + maxHistory);
        this.historyFile = new File(plugin.getDataFolder(), "chat_history.json");
        loadHistory();
    }

    private void loadHistory() {
        if (!historyFile.exists()) {
            messageHistory.clear();
            messageHistory.add(new Message("system", "你是一个游戏违禁词检测工具。通过回复1，违规回复2，违法回复3。只需要回复对应数字，不需要其他任何解释。"));
            saveHistory();
            return;
        }

        try (Reader reader = new FileReader(historyFile, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<ArrayList<Message>>(){}.getType();
            List<Message> loaded = gson.fromJson(reader, type);
            if (loaded != null) {
                messageHistory.clear();
                messageHistory.addAll(loaded);
                plugin.debug("已加载 " + messageHistory.size() + " 条历史消息");
            } else {
                // 如果加载失败，重新初始化
                messageHistory.clear();
                messageHistory.add(new Message("system", "你是一个游戏违禁词检测工具。通过回复1，违规回复2，违法回复3。只需要回复对应数字，不需要其他任何解释。"));
                saveHistory();
            }
        } catch (Exception e) {
            plugin.debug("加载历史记录失败: " + e.getMessage());
            e.printStackTrace();
            // 加载失败时重新初始化
            messageHistory.clear();
            messageHistory.add(new Message("system", "你是一个游戏违禁词检测工具。通过回复1，违规回复2，违法回复3。只需要回复对应数字，不需要其他任何解释。"));
            saveHistory();
        }
    }

    private void saveHistory() {
        try (Writer writer = new FileWriter(historyFile, StandardCharsets.UTF_8)) {
            gson.toJson(messageHistory, writer);
            plugin.debug("已保存 " + messageHistory.size() + " 条历史消息");
        } catch (Exception e) {
            plugin.debug("保存历史记录失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void checkAndTrimHistory() {
        // 始终保留系统消息
        if (messageHistory.size() > maxHistory) {
            plugin.debug("历史记录即将超过限制，当前数量: " + messageHistory.size() + ", 最大限制: " + maxHistory);
            
            // 保存系统消息
            Message systemMessage = messageHistory.get(0);
            
            // 保留最近的消息，而不是全部清空
            // 计算需要保留多少条最新消息
            int keepCount = maxHistory / 2; // 保留一半的容量给新消息
            
            // 创建新的历史记录列表
            List<Message> newHistory = new ArrayList<>();
            newHistory.add(systemMessage); // 首先添加系统消息
            
            // 添加最近的消息
            int startIndex = Math.max(1, messageHistory.size() - keepCount);
            for (int i = startIndex; i < messageHistory.size(); i++) {
                newHistory.add(messageHistory.get(i));
            }
            
            // 更新历史记录
            messageHistory.clear();
            messageHistory.addAll(newHistory);
            
            plugin.debug("已清理历史记录，保留系统消息和最近 " + keepCount + " 条消息，当前历史记录数: " + messageHistory.size());
            
            // 保存更新后的历史记录
            saveHistory();
        }
    }

    public int analyzeMessage(String message) throws Exception {
        // 在添加新消息前检查历史记录大小
        checkAndTrimHistory();
        
        plugin.debug("正在发送请求到: " + baseUrl + "/v1/chat/completions");
        URL url = new URL(baseUrl + "/v1/chat/completions");  // 改为v1接口
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        String formattedMessage = String.format(messageTemplate, message);
        plugin.debug("发送的消息内容: " + formattedMessage);
        
        // 构建完整的消息历史
        StringBuilder messagesJson = new StringBuilder();
        for (Message msg : messageHistory) {
            messagesJson.append(String.format("""
                    {
                        "role": "%s",
                        "content": "%s"
                    },""", 
                    msg.role, msg.content.replace("\"", "\\\"")));
        }
        messagesJson.append(String.format("""
                    {
                        "role": "user",
                        "content": "%s"
                    }""", 
                formattedMessage.replace("\"", "\\\"")));

        String jsonInput = String.format("""
            {
                "model": "%s",
                "messages": [%s],
                "temperature": 0.7
            }""", model, messagesJson.toString());

        plugin.debug("发送的JSON: " + jsonInput);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonInput.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8.name())) {
            String response = scanner.useDelimiter("\\A").next();
            plugin.debug("收到API响应: " + response);
            
            String content = extractContent(response);
            if (content != null) {
                // 添加新的消息到历史记录
                messageHistory.add(new Message("user", formattedMessage));
                messageHistory.add(new Message("assistant", content));
                
                // 保存历史记录
                saveHistory();
                plugin.debug("已添加新对话，当前历史记录数: " + messageHistory.size());
            }
            
            return parseContent(content);
        } catch (Exception e) {
            plugin.debug("API请求失败: " + e.getMessage());
            throw e;
        }
    }

    private String extractContent(String response) {
        try {
            int messageStart = response.indexOf("\"message\"");
            if (messageStart > 0) {
                int contentStart = response.indexOf("\"content\"", messageStart);
                if (contentStart > 0) {
                    contentStart = response.indexOf("\"", contentStart + 9) + 1;
                    int contentEnd = response.indexOf("\"", contentStart);
                    if (contentStart > 0 && contentEnd > contentStart) {
                        return response.substring(contentStart, contentEnd);
                    }
                }
            }
        } catch (Exception e) {
            plugin.debug("提取content时出错: " + e.getMessage());
        }
        return null;
    }

    private int parseContent(String content) {
        if (content != null) {
            plugin.debug("解析到的content内容: " + content);
            if (content.contains("2")) return 2;
            if (content.contains("3")) return 3;
        }
        return 1;
    }

    public static class Message {
        private final String role;
        private final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() { return role; }
        public String getContent() { return content; }
    }
}
