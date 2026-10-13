package com.lincoln.maceguard.website;

import com.google.gson.Gson;
import com.lincoln.maceguard.MaceGuardPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Game state is captured synchronously; database/network work stays off-thread. */
public final class WebsiteExportService implements AutoCloseable {
    private final MaceGuardPlugin plugin;
    private final Gson gson=new Gson();
    private volatile boolean closed;
    private BukkitTask task;
    private CompletableFuture<?> pending;
    private HttpClient client;
    private final WebsiteReadGate reads=new WebsiteReadGate();
    private URI endpoint;
    private String secret;
    public WebsiteExportService(MaceGuardPlugin plugin) { this.plugin=plugin; }
    public void start() {
        try {
            var settings=readSettings();
            if(!settings.getBoolean("enabled",false))return;
            int seconds=configure(settings);
            client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
            task=plugin.getServer().getScheduler().runTaskTimer(plugin,this::publish,20L,seconds*20L);
        } catch(Exception error) {
            plugin.getLogger().warning("Website export disabled; check configuration and its secret environment variable.");
            close();
        }
    }
    private YamlConfiguration readSettings() throws Exception {
        File file=new File(plugin.getDataFolder(),"website-export.yml");
        if(!file.exists())plugin.saveResource("website-export.yml",false);
        var settings=new YamlConfiguration();settings.load(file);return settings;
    }
    private int configure(YamlConfiguration settings) {
        endpoint=WebsiteSignedExport.endpoint(settings.getString("endpoint",""));
        String variable=settings.getString("secret-environment-variable","COMMUNITY_INGEST_SECRET");
        if(!variable.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("Invalid environment variable");
        secret=System.getenv(variable);
        if(secret==null||secret.length()<32)throw new IllegalArgumentException("Missing signing secret");
        int seconds=settings.getInt("interval-seconds",300);
        if(seconds<60||seconds>3600)throw new IllegalArgumentException("Invalid export interval");
        return seconds;
    }
    private boolean busy() {
        return closed||pending!=null&&!pending.isDone()||reads.busy();
    }
    private void publish() {
        if(busy())return;
        long now=System.currentTimeMillis();
        var snapshot=captureRotation(now);
        if(snapshot.isEmpty())return;
        var duels=plugin.getServer().getPluginManager().getPlugin("WarzoneDuels");
        var reader=duelReader(duels);
        reads.submit(work->plugin.getServer().getScheduler().runTaskAsynchronously(plugin,work),
                ()->gson.toJson(PublicWarzoneSnapshot.withLeaders(
                        snapshot.get(),readDuelResults(duels,reader,now).orElse(null))))
                .ifPresent(result->pending=result.orTimeout(10,TimeUnit.SECONDS)
                        .thenCompose(this::sendBody).whenComplete(this::deliveryComplete));
    }
    private Optional<Map<String,Object>> captureRotation(long now) {
        var container=plugin.runtime();
        if(container==null||container.warzone()==null)return Optional.empty();
        var runtime=container.warzone().runtime();
        if(runtime==null||!runtime.config().enabled()||!runtime.region().fullyResolved())return Optional.empty();
        try { return Optional.of(PublicWarzoneSnapshot.capture(runtime,now)); }
        catch(RuntimeException error) { plugin.getLogger().warning("Website rotation capture unavailable.");return Optional.empty(); }
    }
    private Optional<Method> duelReader(Plugin duels) {
        if(duels==null||!duels.isEnabled())return Optional.empty();
        return findReader(duels,"websiteWeeklyResults").or(()->findReader(duels,"websiteWeeklyWins"));
    }
    private Optional<Method> findReader(Plugin duels,String name) {
        try { return Optional.of(duels.getClass().getMethod(name,long.class,long.class)); }
        catch(ReflectiveOperationException absent) { return Optional.empty(); }
    }
    private Optional<Object> readDuelResults(Plugin duels,Optional<Method> reader,long now) {
        if(reader.isEmpty())return Optional.empty();
        try { return Optional.ofNullable(reader.get().invoke(duels,PublicWarzoneSnapshot.weekStart(now),now+1)); }
        catch(ReflectiveOperationException|RuntimeException unavailable) { return Optional.empty(); }
    }
    private CompletableFuture<HttpResponse<Void>> sendBody(String body) {
        if(closed)return CompletableFuture.failedFuture(new IllegalStateException("Exporter closed"));
        var request=WebsiteSignedExport.request(endpoint,secret,body,System.currentTimeMillis(),UUID.randomUUID().toString());
        return client.sendAsync(request,HttpResponse.BodyHandlers.discarding());
    }
    private void deliveryComplete(HttpResponse<Void> response,Throwable error) {
        if(!closed&&(error!=null||response.statusCode()<200||response.statusCode()>=300))
            plugin.getLogger().warning("Website export delivery failed; retrying on the next interval.");
    }
    @Override public void close() {
        closed=true;
        if(task!=null)task.cancel();
        if(pending!=null)pending.cancel(true);
        reads.close();
        if(client!=null)client.shutdownNow();
    }
}
