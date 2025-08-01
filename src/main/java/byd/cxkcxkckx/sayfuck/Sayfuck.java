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
import org.bukkit.entity.Player;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Calendar;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionDefault;

public final class Sayfuck extends JavaPlugin {
    private Map<UUID, Integer> warnings;
    private Map<UUID, Integer> severeWarnings;
    private String openAiApiKey;
    private String apiBaseUrl;
    private String model;
    private String messageTemplate;
    private File warningsFile;
    private File logsFolder;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private OpenAiHandler openAiHandler;
    private boolean debug;
    private File currentLogFile;
    private long nextLogRotation;

    @Override
    public void onEnable() {
        // 注册权限节点
        registerPermissions();
        
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

        // 初始化日志文件夹
        logsFolder = new File(getDataFolder(), "logs");
        if (!logsFolder.exists()) {
            logsFolder.mkdirs();
        }

        // 初始化日志系统
        initializeLogging();
        
        // 启动日志轮换任务
        startLogRotationTask();
        
        debug = getConfig().getBoolean("debug", false);
        getLogger().info("调试模式: " + (debug ? "开启" : "关闭"));
    }

    @Override
    public void onDisable() {
        saveWarnings(); // 保存警告数据
        // OpenAiHandler的历史记录已经在每次对话后自动保存
        getLogger().info("插件已关闭，所有数据已保存");
    }

    private void registerPermissions() {
        try {
            // 注册bypass权限节点
            Permission bypassPerm = new Permission(
                "sayfuck.bypass",
                "允许跳过聊天检测",
                PermissionDefault.OP
            );
            
            // 如果权限还未注册，则注册它
            if (getServer().getPluginManager().getPermission("sayfuck.bypass") == null) {
                getServer().getPluginManager().addPermission(bypassPerm);
                debug("已注册权限节点: sayfuck.bypass");
            }
            
            // 确保OP默认拥有此权限
            bypassPerm.setDefault(PermissionDefault.OP);
            
            debug("权限节点注册完成: sayfuck.bypass (默认OP拥有)");
        } catch (Exception e) {
            getLogger().warning("注册权限节点时出错: " + e.getMessage());
            e.printStackTrace();
        }
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
                Player player = Bukkit.getPlayer(playerUuid);
                if (player != null) {
                    String finalCommand = command.replace("%player%", player.getName());
                    debug("执行警告命令: " + finalCommand);
                    try {
                        boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                        debug("命令执行" + (success ? "成功" : "失败"));
                        if (success) {
                            warnings.put(playerUuid, 0); // 只有在命令执行成功时才清零
                            saveWarnings();
                            getLogger().info("已执行警告命令: " + finalCommand);
                            // 记录执行命令的日志
                            logViolation(player, "达到警告阈值", 2, threshold, finalCommand, true);
                        } else {
                            getLogger().warning("警告命令执行失败: " + finalCommand);
                        }
                    } catch (Exception e) {
                        debug("命令执行出错: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    private void checkSevereWarnings(UUID playerUuid) {
        int threshold = getConfig().getInt("severe-warnings.threshold", 2);
        String command = getConfig().getString("severe-warnings.command", "ban %player% 严重警告过多");

        if (severeWarnings.get(playerUuid) >= threshold) {
            Bukkit.getScheduler().runTask(this, () -> {
                Player player = Bukkit.getPlayer(playerUuid);
                if (player != null) {
                    String finalCommand = command.replace("%player%", player.getName());
                    debug("执行严重警告命令: " + finalCommand);
                    try {
                        boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                        debug("命令执行" + (success ? "成功" : "失败"));
                        if (success) {
                            severeWarnings.put(playerUuid, 0); // 只有在命令执行成功时才清零
                            saveWarnings();
                            getLogger().info("已执行严重警告命令: " + finalCommand);
                            // 记录执行命令的日志
                            logViolation(player, "达到严重警告阈值", 3, threshold, finalCommand, true);
                        } else {
                            getLogger().warning("严重警告命令执行失败: " + finalCommand);
                        }
                    } catch (Exception e) {
                        debug("命令执行出错: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    public void logViolation(Player player, String message, int level, int count, String command, boolean commandExecuted) {
        if (!getConfig().getBoolean("logging.enabled", true)) {
            return;
        }

        String dateFormat = getConfig().getString("logging.date-format", "yyyy-MM-dd HH:mm:ss");
        String format = commandExecuted ? 
                       getConfig().getString("logging.format-with-command") :
                       getConfig().getString("logging.format");
        
        try {
            // 确保latest.log存在
            if (!currentLogFile.exists()) {
                currentLogFile.createNewFile();
            }
            
            // 格式化日志内容
            String logEntry = format
                .replace("%date%", new SimpleDateFormat(dateFormat).format(new Date()))
                .replace("%player%", player.getName())
                .replace("%message%", message)
                .replace("%level%", String.valueOf(level))
                .replace("%count%", String.valueOf(count));

            // 只有在实际执行命令时才添加命令信息
            if (commandExecuted) {
                logEntry = logEntry.replace("%command%", command);
            }
            
            logEntry += System.lineSeparator();
            
            // 追加写入日志
            Files.write(currentLogFile.toPath(), 
                       Collections.singletonList(logEntry), 
                       StandardCharsets.UTF_8,
                       StandardOpenOption.APPEND);
                       
            debug("已记录违规日志: " + logEntry);
        } catch (IOException e) {
            getLogger().warning("无法写入日志文件: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void initializeLogging() {
        if (!logsFolder.exists()) {
            logsFolder.mkdirs();
        }
        
        currentLogFile = new File(logsFolder, "latest.log");
        updateNextRotationTime();
    }

    private void updateNextRotationTime() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        nextLogRotation = cal.getTimeInMillis();
    }

    private void startLogRotationTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            long currentTime = System.currentTimeMillis();
            if (currentTime >= nextLogRotation) {
                rotateLogFile();
                updateNextRotationTime();
            }
        }, 20L * 60, 20L * 60); // 每分钟检查一次
    }

    private void rotateLogFile() {
        if (currentLogFile.exists()) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            String newFileName = dateFormat.format(new Date(nextLogRotation - 24 * 60 * 60 * 1000)) + ".log";
            File newFile = new File(logsFolder, newFileName);
            
            try {
                // 如果已存在同名文件，先备份
                if (newFile.exists()) {
                    int i = 1;
                    while (new File(logsFolder, newFileName.replace(".log", "-" + i + ".log")).exists()) {
                        i++;
                    }
                    newFile = new File(logsFolder, newFileName.replace(".log", "-" + i + ".log"));
                }
                
                Files.move(currentLogFile.toPath(), newFile.toPath());
                debug("日志文件已轮换: " + currentLogFile.getName() + " -> " + newFile.getName());
            } catch (IOException e) {
                getLogger().warning("轮换日志文件失败: " + e.getMessage());
            }
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
