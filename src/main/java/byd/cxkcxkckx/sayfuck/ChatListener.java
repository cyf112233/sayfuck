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
        
        // 检查是否启用管理员豁免和玩家是否是OP
        boolean isExempt = plugin.getConfig().getBoolean("admin.exempt", true) && player.isOp();
        if (isExempt) {
            plugin.debug("玩家 " + player.getName() + " 是管理员(OP)且豁免已开启，跳过所有检查");
            return;  // 直接返回，不进行任何后续处理
        }

        // 不阻塞聊天消息，直接异步处理AI检测
        final String message = event.getMessage();
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.debug("开始处理玩家 " + player.getName() + " 的消息: " + message);
            try {
                int result = plugin.getOpenAiHandler().analyzeMessage(message);
                plugin.debug("AI返回结果: " + result);
                
                // 在主线程中处理警告
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // 再次检查玩家是否是OP（以防在异步过程中权限发生变化）
                    if (plugin.getConfig().getBoolean("admin.exempt", true) && player.isOp()) {
                        plugin.debug("玩家 " + player.getName() + " 是管理员(OP)且豁免已开启，跳过警告");
                        return;
                    }

                    switch (result) {
                        case 2:
                            plugin.addWarning(player.getUniqueId());
                            String warningMsg = plugin.getConfig().getString("warnings.message", "&c你在游戏中的用语不当，警告一次！")
                                    .replace("%count%", String.valueOf(plugin.getWarningCount(player.getUniqueId())))
                                    .replace('&', '§');
                            player.sendMessage(warningMsg);
                            break;
                        case 3:
                            plugin.addSevereWarning(player.getUniqueId());
                            String severeMsg = plugin.getConfig().getString("severe-warnings.message", "&4你发了不该发的，严重警告一次！")
                                    .replace("%count%", String.valueOf(plugin.getSevereWarningCount(player.getUniqueId())))
                                    .replace('&', '§');
                            player.sendMessage(severeMsg);
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
