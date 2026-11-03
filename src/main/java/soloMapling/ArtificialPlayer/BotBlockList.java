package soloMapling.ArtificialPlayer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Manages a block list for bots that are trading.
 * When a bot declines a trade, the trade partner is added to this block list with an expiration time.
 */
public class BotBlockList {

    /**
     * Represents an entry in the block list.
     */
    public static class BlockEntry {
        private final Integer botId;
        private final Integer traderId;
        private final Instant blockTime;
        private final Instant expirationTime;

        /**
         * Creates a new block entry.
         *
         * @param botId          The ID of the bot that created the block
         * @param traderId       The ID of the trader being blocked
         * @param blockTime      The time when the block was created
         * @param expirationTime The time when the block will expire
         */
        public BlockEntry(Integer botId, Integer traderId, Instant blockTime, Instant expirationTime) {
            this.botId = botId;
            this.traderId = traderId;
            this.blockTime = blockTime;
            this.expirationTime = expirationTime;
        }

        // Getters
        public Integer getBotId() {
            return botId;
        }

        public Integer getTraderId() {
            return traderId;
        }

        public Instant getBlockTime() {
            return blockTime;
        }

        public Instant getExpirationTime() {
            return expirationTime;
        }

        /**
         * Checks if this block entry has expired.
         *
         * @param currentTime The current time to check against
         * @return true if the block has expired, false otherwise
         */
        public boolean isExpired(Instant currentTime) {
            return currentTime.isAfter(expirationTime);
        }

        @Override
        public String toString() {
            return "BlockEntry{" +
                    "botId='" + botId + '\'' +
                    ", traderId='" + traderId + '\'' +
                    ", blockTime=" + blockTime +
                    ", expirationTime=" + expirationTime +
                    '}';
        }
    }

    private static final BotBlockList botBlockList = new BotBlockList();

    /**
     * A concurrent set: a bot that declines a trade adds to it on the trade thread while other bots read and sweep
     * it on their ticks. Entries are compared by identity, so two blocks of the same pair stay two entries, as they
     * did in the list this replaced. Reads skip an expired entry whether or not a sweep has removed it yet.
     */
    private final Set<BlockEntry> blockList = ConcurrentHashMap.newKeySet();

    public BotBlockList() {
    }

    public static BotBlockList getInstance() {
        return botBlockList;
    }

    /**
     * Adds a trader to the block list for a specific bot, sweeping expired entries out of the set first.
     *
     * @param botId                The ID of the bot creating the block
     * @param traderId             The ID of the trader to block
     * @param blockDurationSeconds How long the block should last in seconds
     * @return The created block entry
     */
    public BlockEntry addToBlockList(Integer botId, Integer traderId, long blockDurationSeconds) {
        removeExpiredEntries();

        Instant now = Instant.now();
        Instant expirationTime = now.plusSeconds(blockDurationSeconds);

        BlockEntry entry = new BlockEntry(botId, traderId, now, expirationTime);
        blockList.add(entry);

        return entry;
    }

    public BlockEntry addToBlockList(Integer botId, Integer traderId) {
        return addToBlockList(botId, traderId, 300);
    }

    /**
     * Checks if a trader is currently blocked by a specific bot.
     *
     * @param botId    The ID of the bot
     * @param traderId The ID of the trader
     * @return true if the trader is blocked by the bot, false otherwise
     */
    public boolean isBlocked(Integer botId, Integer traderId) {
        Instant now = Instant.now();
        return blockList.stream()
                .anyMatch(entry -> !entry.isExpired(now) && entry.getBotId().equals(botId)
                        && entry.getTraderId().equals(traderId));
    }

    /**
     * Gets the block entry for a specific bot and trader if it exists. When the pair was blocked more than once,
     * the earliest live block is returned.
     *
     * @param botId    The ID of the bot
     * @param traderId The ID of the trader
     * @return Optional containing the block entry if found, empty otherwise
     */
    public Optional<BlockEntry> getBlockEntry(Integer botId, Integer traderId) {
        return liveEntries()
                .filter(entry -> entry.getBotId().equals(botId) && entry.getTraderId().equals(traderId))
                .findFirst();
    }

    /**
     * The sweep: removes every expired block entry from the set.
     */
    public void removeExpiredEntries() {
        Instant now = Instant.now();
        blockList.removeIf(entry -> entry.isExpired(now));
    }

    /**
     * Manually removes a block entry for a specific bot and trader.
     *
     * @param botId    The ID of the bot
     * @param traderId The ID of the trader
     * @return true if an entry was removed, false otherwise
     */
    public boolean removeBlock(Integer botId, Integer traderId) {
        return blockList.removeIf(entry ->
                entry.getBotId().equals(botId) && entry.getTraderId().equals(traderId));
    }

    /**
     * Gets all current block entries, oldest first (expired entries are left out).
     *
     * @return A list of all active block entries
     */
    public List<BlockEntry> getBlockList() {
        return new ArrayList<>(liveEntries().toList());
    }

    /**
     * Gets all block entries for a specific bot.
     *
     * @param botId The ID of the bot
     * @return A list of all active block entries for the bot
     */
    public List<BlockEntry> getBlocksForBot(Integer botId) {
        return liveEntries()
                .filter(entry -> entry.getBotId().equals(botId))
                .toList();
    }

    /**
     * Gets all traders blocked by a specific bot.
     *
     * @param botId The ID of the bot
     * @return A list of trader IDs blocked by the bot
     */
    public List<Integer> getBlockedTraders(Integer botId) {
        return liveEntries()
                .filter(entry -> entry.getBotId().equals(botId))
                .map(BlockEntry::getTraderId)
                .toList();
    }

    /**
     * Clears all block entries.
     */
    public void clearBlockList() {
        blockList.clear();
    }

    /**
     * Gets the total number of active blocks.
     *
     * @return The count of non-expired block entries
     */
    public int getActiveBlockCount() {
        Instant now = Instant.now();
        return (int) blockList.stream().filter(entry -> !entry.isExpired(now)).count();
    }

    /** A snapshot of the entries that have not expired, oldest block first. */
    private Stream<BlockEntry> liveEntries() {
        Instant now = Instant.now();
        return blockList.stream()
                .filter(entry -> !entry.isExpired(now))
                .sorted(Comparator.comparing(BlockEntry::getBlockTime));
    }
}
