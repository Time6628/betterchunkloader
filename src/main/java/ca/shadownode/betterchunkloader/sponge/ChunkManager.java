package ca.shadownode.betterchunkloader.sponge;

import ca.shadownode.betterchunkloader.sponge.data.ChunkLoader;
import ca.shadownode.betterchunkloader.sponge.events.ChunkLoadingCallback;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.world.Chunk;
import org.spongepowered.api.world.ChunkTicketManager;
import org.spongepowered.api.world.ChunkTicketManager.LoadingTicket;
import org.spongepowered.api.world.World;

public class ChunkManager {

    private final BetterChunkLoader plugin;

    private final Optional<ChunkTicketManager> ticketManager;

    private final HashMap<UUID, Optional<LoadingTicket>> tickets = new HashMap<>();

    public ChunkManager(BetterChunkLoader plugin) {
        this.plugin = plugin;
        try {
            boolean overridesEnabled = getField(ForgeChunkManager.class, "overridesEnabled").getBoolean(null);

            if (!overridesEnabled) {
                getField(ForgeChunkManager.class, "overridesEnabled").set(null, true);
            }

            Map<String, Integer> ticketConstraints = (Map<String, Integer>) getField(ForgeChunkManager.class, "ticketConstraints").get(null);
            Map<String, Integer> chunkConstraints = (Map<String, Integer>) getField(ForgeChunkManager.class, "chunkConstraints").get(null);

            ticketConstraints.put("betterchunkloader", Integer.MAX_VALUE);
            chunkConstraints.put("betterchunkloader", Integer.MAX_VALUE);

        } catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException ex) {
            plugin.getLogger().error("ChunkManager failed to force chunk constraints", ex);
        }
        ticketManager = Sponge.getServiceManager().provide(ChunkTicketManager.class);
        if (ticketManager.isPresent()) {
            ticketManager.get().registerCallback(plugin, new ChunkLoadingCallback(plugin));
            if (plugin.getConfig().getCore().debug) {
                plugin.getLogger().debug("MaxTickets: " + ticketManager.get().getMaxTickets(plugin.pluginContainer));
                Sponge.getServer().getWorlds().stream().map((world) -> {
                    plugin.getLogger().debug("AvailTicket : " + world.getName() + ":" + ticketManager.get().getAvailableTickets(plugin.pluginContainer, world));
                    return world;
                }).forEachOrdered((world) -> {
                    plugin.getLogger().debug("ForcedChunks : " + world.getName() + ":" + ticketManager.get().getForcedChunks(world));
                });
            }
        }
    }

    public boolean loadChunkLoader(ChunkLoader chunkLoader) {
        Optional<World> world = Sponge.getServer().getWorld(chunkLoader.getWorld());
        if (!world.isPresent()) {
            return false;
        }
        Optional<Chunk> mainChunk = world.get().getChunk(chunkLoader.getChunk());
        if (!mainChunk.isPresent()) {
            return false;
        }
        List<Chunk> chunks = getChunks(chunkLoader.getRadius(), mainChunk.get());
        chunks.forEach((chunk) -> {
            loadChunk(chunkLoader, chunk);
        });
        return true;
    }

    public boolean unloadChunkLoader(ChunkLoader chunkLoader) {
        Optional<World> world = Sponge.getServer().getWorld(chunkLoader.getWorld());
        if (!world.isPresent()) {
            return false;
        }
        Optional<Chunk> mainChunk = world.get().getChunk(chunkLoader.getChunk());
        if (!mainChunk.isPresent()) {
            return false;
        }
        List<Chunk> chunks = getChunks(chunkLoader.getRadius(), mainChunk.get());
        chunks.forEach((chunk) -> {
            unloadChunk(chunkLoader, chunk);
        });
        return true;
    }

    public Optional<WorldServer> getWorld(String worldName) {
        for (WorldServer world : DimensionManager.getWorlds()) {
            if (world.getWorldInfo().getWorldName().equalsIgnoreCase(worldName)) {
                return Optional.of(world);
            }
        }
        return Optional.empty();
    }

    /**
     *
     * Loads chunk using old or new ticket.
     *
     * @param chunkLoader
     * @param chunk
     * @return
     */
    public boolean loadChunk(ChunkLoader chunkLoader, Chunk chunk) {
        if (!ticketManager.isPresent()) {
            return false;
        }
        Optional<LoadingTicket> ticket;
        if (tickets.containsKey(chunkLoader.getUniqueId()) && tickets.get(chunkLoader.getUniqueId()).isPresent()) {
            ticket = tickets.get(chunkLoader.getUniqueId());
        } else {
            ticket = ticketManager.get().createTicket(plugin, chunk.getWorld());
            tickets.put(chunkLoader.getUniqueId(), ticket);
        }
        if (ticket.isPresent() && chunk != null) {
            ticket.get().forceChunk(chunk.getPosition());
            if (plugin.getConfig().getCore().debug) {
                plugin.getLogger().debug("LOAD");
                plugin.getLogger().debug("CList: " + Arrays.toString(ticket.get().getChunkList().toArray()));
            }
            return true;
        }
        return false;
    }

    /**
     * Unloads chunk using tickets.
     *
     * @param chunkLoader
     * @param chunk
     * @return
     */
    public boolean unloadChunk(ChunkLoader chunkLoader, Chunk chunk) {
        if (!ticketManager.isPresent()) {
            return false;
        }
        if (tickets.containsKey(chunkLoader.getUniqueId())) {
            Optional<LoadingTicket> ticket = tickets.get(chunkLoader.getUniqueId());
            if (ticket.isPresent() && chunk != null) {
                ticket.get().unforceChunk(chunk.getPosition());
                if (plugin.getConfig().getCore().debug) {
                    plugin.getLogger().debug("UNLOAD");
                    plugin.getLogger().debug("CList: " + Arrays.toString(ticket.get().getChunkList().toArray()));
                }
                return true;
            }
        }
        return false;
    }

    /*
        Gets all tickets controlled by this library.
     */
    public Map<UUID, Optional<LoadingTicket>> getTickets() {
        return tickets;
    }

    public List<Chunk> getChunks(Integer radius, Chunk chunk) {
        List<Chunk> chunks = new ArrayList<>(Arrays.asList());
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                Optional<Chunk> found = chunk.getWorld().getChunk(chunk.getPosition().add(x, 0, z));
                if (found.isPresent()) {
                    chunks.add(found.get());
                }
            }
        }
        return chunks;
    }

    private Field getField(Class<?> targetClass, String fieldName) throws NoSuchFieldException, SecurityException {
        Field field = targetClass.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
    }
}
