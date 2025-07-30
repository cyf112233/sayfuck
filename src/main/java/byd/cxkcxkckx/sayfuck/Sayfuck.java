package byd.cxkcxkckx.sayfuck;

import org.bukkit.plugin.java.JavaPlugin;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.bukkit.Bukkit;

public final class Sayfuck extends JavaPlugin {
    private Map<UUID, Integer> warnings;
    private Map<UUID, Integer> severeWarnings;
    private String openAiApiKey;
    private String apiBaseUrl;
    private String model;
    private String messageTemplate;
    private File warningsFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private OpenAiHandler openAiHandler;
    private boolean debug;

    @Override
    public void onEnable() {
        // 初始化警告系统
        warnings = new HashMap<>();
        severeWarnings = new HashMap<>();
        
        // 保存默认配置
        saveDefaultConfig();
        openAiApiKey = getConfig().getString("openai-api-key", "your-api-key");
        apiBaseUrl = getConfig().getString("api-base-url", "https://allgpt.xianyuw.cn");
        model = getConfig().getString("model", "gpt-3.5-turbo");
        messageTemplate = getConfig().getString("message-template",
            "游戏公开聊天：(%s)   " +
            "你将作为游戏违禁词检测工具   " +
            "通过请回复1   " +
            "违规请回复2   " +
            "违法返回为3   " +
            "不用解释或回复其他任何内容   " +
            "（注意，只有明显违反法律法规或者是脏话才能判断违规，网络用词无需违规）"
        );
        
        // 初始化OpenAI处理器
        openAiHandler = new OpenAiHandler(this, openAiApiKey, apiBaseUrl, model, messageTemplate);
        
        // 注册监听器
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);

        // 初始化警告文件
        warningsFile = new File(getDataFolder(), "warnings.json");
        loadWarnings();

        debug = getConfig().getBoolean("debug", false);
        getLogger().info("调试模式: " + (debug ? "开启" : "关闭"));
    }

    @Override
    public void onDisable() {
        saveWarnings(); // 保存警告数据
        // OpenAiHandler的历史记录已经在每次对话后自动保存
        getLogger().info("插件已关闭，所有数据已保存");
    }

    private void loadWarnings() {
        if (!warningsFile.exists()) {
            warnings = new HashMap<>();
            severeWarnings = new HashMap<>();
            saveWarnings();
            return;
        }

        try (Reader reader = new FileReader(warningsFile, StandardCharsets.UTF_8)) {
            WarningData data = gson.fromJson(reader, WarningData.class);
            warnings = data.warnings;
            severeWarnings = data.severeWarnings;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveWarnings() {
        try (Writer writer = new FileWriter(warningsFile, StandardCharsets.UTF_8)) {
            WarningData data = new WarningData(warnings, severeWarnings);
            gson.toJson(data, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getOpenAiApiKey() {
        return openAiApiKey;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public String getModel() {
        return model;
    }

    public String getMessageTemplate() {
        return messageTemplate;
    }

    public void addWarning(UUID playerUuid) {
        warnings.merge(playerUuid, 1, Integer::sum);
        checkWarnings(playerUuid);
        saveWarnings();
    }

    public void addSevereWarning(UUID playerUuid) {
        severeWarnings.merge(playerUuid, 1, Integer::sum);
        checkSevereWarnings(playerUuid);
        saveWarnings();
    }

    public int getWarningCount(UUID playerUuid) {
        return warnings.getOrDefault(playerUuid, 0);
    }

    public int getSevereWarningCount(UUID playerUuid) {
        return severeWarnings.getOrDefault(playerUuid, 0);
    }

    private void checkWarnings(UUID playerUuid) {
        int threshold = getConfig().getInt("warnings.threshold", 3);
        String command = getConfig().getString("warnings.command", "kick %player% 警告过多");

        if (warnings.get(playerUuid) >= threshold) {
            Bukkit.getScheduler().runTask(this, () -> {
                String finalCommand = command.replace("%player%", Bukkit.getPlayer(playerUuid).getName());
                debug("执行警告命令: " + finalCommand);
                try {
                    boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                    debug("命令执行" + (success ? "成功" : "失败"));
                    if (success) {
                        warnings.put(playerUuid, 0); // 只有在命令执行成功时才清零
                        saveWarnings();
                        getLogger().info("已执行警告命令: " + finalCommand);
                    } else {
                        getLogger().warning("警告命令执行失败: " + finalCommand);
                    }
                } catch (Exception e) {
                    debug("命令执行出错: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        }
    }

    private void checkSevereWarnings(UUID playerUuid) {
        int threshold = getConfig().getInt("severe-warnings.threshold", 2);
        String command = getConfig().getString("severe-warnings.command", "ban %player% 严重警告过多");

        if (severeWarnings.get(playerUuid) >= threshold) {
            Bukkit.getScheduler().runTask(this, () -> {
                String finalCommand = command.replace("%player%", Bukkit.getPlayer(playerUuid).getName());
                debug("执行严重警告命令: " + finalCommand);
                try {
                    boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                    debug("命令执行" + (success ? "成功" : "失败"));
                    if (success) {
                        severeWarnings.put(playerUuid, 0); // 只有在命令执行成功时才清零
                        saveWarnings();
                        getLogger().info("已执行严重警告命令: " + finalCommand);
                    } else {
                        getLogger().warning("严重警告命令执行失败: " + finalCommand);
                    }
                } catch (Exception e) {
                    debug("命令执行出错: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        }
    }

    public OpenAiHandler getOpenAiHandler() {
        return openAiHandler;
    }

    public void debug(String message) {
        if (debug) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    private static class WarningData {
        Map<UUID, Integer> warnings;
        Map<UUID, Integer> severeWarnings;

        public WarningData(Map<UUID, Integer> warnings, Map<UUID, Integer> severeWarnings) {
            this.warnings = warnings;
            this.severeWarnings = severeWarnings;
        }
    }
}
