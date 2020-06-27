package dev.sergivos.playersync;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.evilblock.pidgin.Pidgin;
import net.evilblock.pidgin.PidginOptions;
import net.evilblock.pidgin.message.Message;
import net.evilblock.pidgin.message.handler.IncomingMessageHandler;
import net.evilblock.pidgin.message.listener.MessageListener;
import net.evilblock.pidgin.shaded.com.google.gson.JsonObject;
import net.evilblock.pidgin.shaded.redis.clients.jedis.Jedis;
import net.evilblock.pidgin.shaded.redis.clients.jedis.JedisPool;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class PlayerSync extends JavaPlugin implements Listener, MessageListener {
    private Cache<UUID, JsonObject> invCache;
    private JedisPool jedisPool;
    private Pidgin pidgin;

    @Override
    public void onEnable() {
        invCache = CacheBuilder.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .expireAfterAccess(15, TimeUnit.SECONDS)
                .build();

        jedisPool = new JedisPool("127.0.0.1", 6379);
        pidgin = new Pidgin("test", jedisPool, new PidginOptions(true));

        pidgin.registerListener(this);
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        invCache.cleanUp();
        jedisPool.close();
    }

    private void applyContents(Player player, JsonObject jsonObject) {
        try {
            player.getInventory().setArmorContents(InventoryUtil.itemStackArrayFromBase64(jsonObject.get("armor").getAsString()));
            player.getInventory().setContents(InventoryUtil.itemStackArrayFromBase64(jsonObject.get("inventory").getAsString()));
            player.getEnderChest().setContents(InventoryUtil.itemStackArrayFromBase64(jsonObject.get("enderchest").getAsString()));
        } catch (IOException e) {
            e.printStackTrace();
            player.sendMessage(ChatColor.RED + "ERROR LOADING YOUR STUFF! D:");
            return;
        }

        player.setFoodLevel(Integer.parseInt(jsonObject.get("hunger").getAsString()));
        player.setLevel(Integer.parseInt(jsonObject.get("xp").getAsString()));

        getLogger().info(player.getName() + "'s inventory has been applied!");
        player.sendMessage(ChatColor.GREEN + "Your inventory has been applied!");
        invCache.invalidate(player.getUniqueId()); // remove from cache
    }

    private void applyContents(Player player, Map<String, String> data) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("player", data.get("player"));
        jsonObject.addProperty("armor", data.get("armor"));
        jsonObject.addProperty("inventory", data.get("inventory"));
        jsonObject.addProperty("enderchest", data.get("enderchest"));
        jsonObject.addProperty("hunger", data.get("hunger"));
        jsonObject.addProperty("xp", data.get("xp"));
        applyContents(player, jsonObject);
    }

    @IncomingMessageHandler(id = "INVENTORY_UPDATE")
    public void onInventoryUpdate(JsonObject jsonObject) {
        getLogger().info("Received inventory from " + jsonObject.get("player").getAsString());
        UUID playerUUID = UUID.fromString(jsonObject.get("player").getAsString());

        Player player = Bukkit.getPlayer(playerUUID);
        if (player == null || !player.isOnline()) {
            invCache.put(playerUUID, jsonObject);
            return;
        }

        applyContents(player, jsonObject);
    }

    @EventHandler
    public void onPlayerLogin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        JsonObject jsonObject = invCache.getIfPresent(player.getUniqueId());
        if (jsonObject != null) {
            applyContents(player, jsonObject);
            return;
        }
        player.sendMessage(ChatColor.RED + "You have no inventory in cache... Huh? Should restore from redis in a few...");

        try (Jedis jedis = jedisPool.getResource()) {
            if (!jedis.exists("data:" + player.getUniqueId().toString())) {
                player.sendMessage(ChatColor.RED + "Not even in redis cache... :(");
                return;
            }

            applyContents(event.getPlayer(), jedis.hgetAll("data:" + player.getUniqueId().toString()));
            // TODO: remove from redis once applied

            jedis.resetState();
        }
    }

    @EventHandler
    public void onPostQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        Map<String, String> data = new HashMap<>();
        data.put("player", player.getUniqueId().toString());
        data.put("armor", InventoryUtil.itemStackArrayToBase64(player.getInventory().getArmorContents()));
        data.put("inventory", InventoryUtil.itemStackArrayToBase64(player.getInventory().getContents()));
        data.put("enderchest", InventoryUtil.itemStackArrayToBase64(player.getEnderChest().getContents()));
        data.put("hunger", String.valueOf(player.getFoodLevel()));
        data.put("xp", String.valueOf(player.getLevel()));

        pidgin.sendMessage(new Message("INVENTORY_UPDATE", data));

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hmset("data:" + player.getUniqueId().toString(), data);
            jedis.expire("data:" + player.getUniqueId().toString(), 2 * 60); // expiry after 2 minutes
            jedis.resetState();
        }
    }

}
