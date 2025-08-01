package byd.cxkcxkckx.sayfuck;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public class ChatListener implements Listener {
    private final Sayfuck plugin;

    public ChatListener(Sayfuck plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        final Player player = event.getPlayer();
        
        // 检查是否启用管理员豁免和玩家是否有bypass权限
        boolean isExempt = plugin.getConfig().getBoolean("admin.exempt", true) 
            && player.hasPermission("sayfuck.bypass");
        if (isExempt) {
            plugin.debug("玩家 " + player.getName() + " 有bypass权限且豁免已开启，跳过所有检查");
            return;
        }

        final String message = event.getMessage();
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.debug("开始处理玩家 " + player.getName() + " 的消息: " + message);
            try {
                int result = plugin.getOpenAiHandler().analyzeMessage(message);
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
                            // 记录警告日志
                            plugin.logViolation(player, message, 2, plugin.getWarningCount(player.getUniqueId()), 
                                plugin.getConfig().getString("warnings.command"));
                            break;
                        case 3:
                            plugin.addSevereWarning(player.getUniqueId());
                            String severeMsg = plugin.getConfig().getString("severe-warnings.message")
                                    .replace("%count%", String.valueOf(plugin.getSevereWarningCount(player.getUniqueId())))
                                    .replace('&', '§');
                            player.sendMessage(severeMsg);
                            // 记录严重警告日志
                            plugin.logViolation(player, message, 3, plugin.getSevereWarningCount(player.getUniqueId()),
                                plugin.getConfig().getString("severe-warnings.command"));
                            break;
                    }
                });
            } catch (Exception e) {
                plugin.debug("处理消息时发生错误: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
}
