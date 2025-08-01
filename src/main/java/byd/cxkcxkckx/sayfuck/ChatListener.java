package byd.cxkcxkckx.sayfuck;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.HashMap;
import java.util.Map;

public class ChatListener implements Listener {
    private final Sayfuck plugin;

    public ChatListener(Sayfuck plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        final Player player = event.getPlayer();
        
        // 检查消息长度
        if (event.getMessage().length() > 100) { // 设置合理的最大长度限制
            plugin.debug("玩家 " + player.getName() + " 发送的消息过长，可能是尝试注入攻击");
            return;
        }
        
        // 检查是否启用管理员豁免和玩家是否有bypass权限
        boolean isExempt = plugin.getConfig().getBoolean("admin.exempt", true) 
            && player.hasPermission("sayfuck.bypass");
        if (isExempt) {
            plugin.debug("玩家 " + player.getName() + " 有bypass权限且豁免已开启，跳过所有检查");
            return;
        }

        // 预处理玩家消息
        String originalMessage = event.getMessage();
        String cleanedMessage = cleanMessage(originalMessage);
        plugin.debug("原始消息: " + originalMessage);
        plugin.debug("处理后的消息: " + cleanedMessage);
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.debug("开始处理玩家 " + player.getName() + " 的消息");
            try {
                int result = plugin.getOpenAiHandler().analyzeMessage(cleanedMessage);
                plugin.debug("AI返回结果: " + result);
                
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (plugin.getConfig().getBoolean("admin.exempt", true) 
                        && player.hasPermission("sayfuck.bypass")) {
                        return;
                    }

                    switch (result) {
                        case 2:
                            plugin.addWarning(player.getUniqueId());
                            String warningMsg = plugin.getConfig().getString("warnings.message")
                                    .replace("%count%", String.valueOf(plugin.getWarningCount(player.getUniqueId())))
                                    .replace('&', '§');
                            player.sendMessage(warningMsg);
                            // 记录普通违规日志(不包含命令)
                            plugin.logViolation(player, cleanedMessage, 2, plugin.getWarningCount(player.getUniqueId()), "", false);
                            break;
                        case 3:
                            plugin.addSevereWarning(player.getUniqueId());
                            String severeMsg = plugin.getConfig().getString("severe-warnings.message")
                                    .replace("%count%", String.valueOf(plugin.getSevereWarningCount(player.getUniqueId())))
                                    .replace('&', '§');
                            player.sendMessage(severeMsg);
                            // 记录严重违规日志(不包含命令)
                            plugin.logViolation(player, cleanedMessage, 3, plugin.getSevereWarningCount(player.getUniqueId()), "", false);
                            break;
                    }
                });
            } catch (Exception e) {
                plugin.debug("处理消息时发生错误: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * 清理消息中的特殊字符
     */
    private String cleanMessage(String message) {
        if (message == null) return "";
        
        // 移除括号字符本身，但保留内容
        message = message.replaceAll("[\\[\\]()（）《》「」『』【】{}⟨⟩〈〉｛｝«»‹›〔〕⦅⦆<>〖〗]", "");
        
        // 移除引号和反斜杠
        message = message.replaceAll("[\\\\\\[\\]()（）《》「」『』【】{}⟨⟩〈〉｛｝«»‹›〔〕⦅⦆<>〖〗\"']", "");
        
        // 第三步：移除控制字符和特殊Unicode字符
        message = message.replaceAll("[\\p{C}\\p{Cf}\\p{Co}\\p{Cn}]+", "");
        
        // 第四步：替换常见的替代字符
        Map<String, String> replacements = new HashMap<>();
        replacements.put("⒈", "1");
        replacements.put("⑴", "1");
        replacements.put("⓵", "1");
        replacements.put("①", "1");
        replacements.put("⒉", "2");
        replacements.put("⑵", "2");
        replacements.put("⓶", "2");
        replacements.put("②", "2");
        replacements.put("⒊", "3");
        replacements.put("⑶", "3");
        replacements.put("⓷", "3");
        replacements.put("③", "3");
        
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            message = message.replace(entry.getKey(), entry.getValue());
        }
        
        // 第五步：处理一些特殊的绕过字符
        message = message// 统一替换为同义词
            .replace("⑨", "9")
            .replace("⓽", "9")
            .replace("㈨", "9")
            .replace("⑥", "6")
            .replace("⓺", "6")
            .replace("㈥", "6")
            .replace("⑦", "7")
            .replace("⓻", "7")
            .replace("㈦", "7");
            
        // 第六步：移除重复的标点符号
        message = message.replaceAll("[\\.。，,！!？?]{2,}", "$1");
        
        // 第七步：处理零宽字符和不可见字符
        message = message.replaceAll("[\\u200B-\\u200F\\uFEFF\\u202A-\\u202E]+", "");
        
        // 返回处理后的消息，并确保消息不为空
        return message.trim().isEmpty() ? "[空消息]" : message.trim();
    }
}
