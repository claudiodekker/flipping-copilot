package com.flippingcopilot.model;

import com.flippingcopilot.controller.Persistance;
import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OfferManager {

    private static final String OFFER_FILE_TEMPLATE = "acc_%d_%d.json";

    // dependencies
    private final Gson gson;
    private final ScheduledExecutorService executorService;

    // state
    @Getter
    @Setter
    private int lastViewedSlotItemId = -1;
    @Getter
    @Setter
    private long lastViewedSlotItemPrice = -1;
    @Getter
    @Setter
    private int lastViewedSlotPriceTime = 0;
    @Getter
    @Setter
    private int viewedSlotItemId = -1;
    @Getter
    @Setter
    private long viewedSlotItemPrice = -1;
    @Getter
    @Setter
    boolean offerJustPlaced = false;

    private final Map<Long, Map<Integer, SavedOffer>> cachedOffers = new HashMap<>();
    private final Map<Long, Map<Integer, File>> files = new HashMap<>();
    private final Map<Long, Map<Integer, SavedOffer>> lastSaved = new HashMap<>();

    public synchronized SavedOffer loadOffer(Long accountHash, Integer slot) {
        Map<Integer, SavedOffer> slotToOffer = cachedOffers.computeIfAbsent(accountHash, (k) -> new HashMap<>());
        return slotToOffer.computeIfAbsent(slot, (k) -> readJsonFile(getFile(accountHash, k), SavedOffer.class));
    }

    public synchronized void saveOffer(Long accountHash, Integer slot, SavedOffer offer) {
        Map<Integer, SavedOffer> slotToOffer = cachedOffers.computeIfAbsent(accountHash, (k) -> new HashMap<>());
        slotToOffer.put(slot, offer);
        saveAsync(accountHash, slot);
    }

    private void saveAsync(Long accountHash, Integer slot) {
        executorService.submit(() -> save(accountHash, slot));
    }

    public synchronized void saveAll() {
        for (Long accountHash : cachedOffers.keySet()) {
            for (Integer slot : cachedOffers.get(accountHash).keySet()) {
                save(accountHash, slot);
            }
        }
    }

    private void save(Long accountHash, Integer slot) {
        File file = getFile(accountHash, slot);
        synchronized (file) {
            SavedOffer offer = loadOffer(accountHash, slot);
            Map<Integer, SavedOffer> slotToLastSaved = lastSaved.computeIfAbsent(accountHash, (k) -> new HashMap<>());

            if (!Objects.equals(offer, slotToLastSaved.get(slot))) {
                slotToLastSaved.put(slot, Persistance.writeAtomically(file, gson.toJson(offer)) ? offer : null);
            }
        }
    }

    private File getFile(Long accountHash, Integer slot) {
        Map<Integer, File> slotToFile = files.computeIfAbsent(accountHash, (k) -> new HashMap<>());
        return slotToFile.computeIfAbsent(slot, (k) -> new File(Persistance.directory, String.format(OFFER_FILE_TEMPLATE, accountHash, slot)));
    }

    private static final String SEEN_FILE_TEMPLATE = "acc_%d_seen.json";

    public static class SeenData {
        public String name;
        public Map<Integer, Long> slots = new HashMap<>();
    }

    private final Object seenLock = new Object();
    private final Map<Long, SeenData> seenCache = new HashMap<>();
    private final Set<Long> seenDirty = new HashSet<>();

    public void stampSeen(long accountHash, int slot, long epochSeconds, String name) {
        stampSlots(accountHash, epochSeconds, name, slot);
    }

    public void stampSeenAll(long accountHash, long epochSeconds) {
        stampSlots(accountHash, epochSeconds, null, 0, 1, 2, 3, 4, 5, 6, 7);
    }

    private void stampSlots(long accountHash, long epochSeconds, String name, int... slots) {
        synchronized (seenLock) {
            SeenData d = seededSeen(accountHash);
            if (name != null && !name.isEmpty()) {
                d.name = name;
            }
            for (int slot : slots) {
                d.slots.merge(slot, epochSeconds, Math::max);
            }

            seenDirty.add(accountHash);
        }

        executorService.submit(() -> writeSeen(accountHash));
    }

    public void flushSeen() {
        synchronized (seenLock) {
            for (Long accountHash : new HashSet<>(seenDirty)) {
                writeSeenLocked(accountHash);
            }
        }
    }

    public SeenData loadSeen(long accountHash) {
        SeenData result = readSeenFromDisk(accountHash);

        synchronized (seenLock) {
            SeenData mem = seenCache.get(accountHash);
            if (mem != null) {
                mem.slots.forEach((slot, t) -> result.slots.merge(slot, t, Math::max));
                if (result.name == null || result.name.isEmpty()) {
                    result.name = mem.name;
                }
            }
        }

        return result;
    }

    private SeenData seededSeen(long accountHash) {
        return seenCache.computeIfAbsent(accountHash, this::readSeenFromDisk);
    }

    private void writeSeen(long accountHash) {
        synchronized (seenLock) {
            writeSeenLocked(accountHash);
        }
    }

    private void writeSeenLocked(long accountHash) {
        SeenData d = seenCache.get(accountHash);
        if (d != null && Persistance.writeAtomically(seenFile(accountHash), gson.toJson(d))) {
            seenDirty.remove(accountHash);
        }
    }

    private static final Pattern OFFER_FILE_PATTERN = Pattern.compile("^acc_(-?\\d+)_([0-7])\\.json$");

    @lombok.AllArgsConstructor
    public static class RestingOffer {
        public final long accountHash;
        public final int slot;
        public final SavedOffer offer;
        public final long lastSeen;
        public final String accountName;

        public static String slotKey(long accountHash, int slot) {
            return accountHash + ":" + slot;
        }

        public String slotKey() {
            return slotKey(accountHash, slot);
        }

        public String fingerprint() {
            return slotKey() + ":" + offer.getItemId() + ":" + offer.getPrice() + ":" + lastSeen;
        }
    }

    public static class EnumerationResult {
        public final List<RestingOffer> resting = new ArrayList<>();
        public final Set<String> resolvedSlotKeys = new HashSet<>();
    }

    public EnumerationResult listRestingOffers() {
        EnumerationResult result = new EnumerationResult();
        File[] files = Persistance.directory.listFiles();
        if (files == null) {
            return result;
        }

        Map<Long, SeenData> seenByAccount = new HashMap<>();
        for (File f : files) {
            Matcher m = OFFER_FILE_PATTERN.matcher(f.getName());
            if (!m.matches()) {
                continue;
            }

            long accountHash = Long.parseLong(m.group(1));
            int slot = Integer.parseInt(m.group(2));
            if (accountHash == -1) {
                continue;
            }

            SavedOffer offer;
            try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
                offer = gson.fromJson(reader, SavedOffer.class);
            } catch (JsonSyntaxException | JsonIOException | IOException e) {
                log.debug("skipping unparseable offer file {} this poll", f, e);
                continue;
            }

            boolean resting = offer != null && (offer.getState() == GrandExchangeOfferState.BUYING || offer.getState() == GrandExchangeOfferState.SELLING);
            if (!resting) {
                result.resolvedSlotKeys.add(RestingOffer.slotKey(accountHash, slot));
                continue;
            }

            // a torn sidecar reads as an empty map, so every slot falls out on the null check below
            SeenData seen = seenByAccount.computeIfAbsent(accountHash, this::loadSeen);
            Long lastSeen = seen.slots.get(slot);
            if (lastSeen == null || lastSeen == 0) {
                continue;
            }

            result.resting.add(new RestingOffer(accountHash, slot, offer, lastSeen, seen.name));
            result.resolvedSlotKeys.add(RestingOffer.slotKey(accountHash, slot));
        }

        return result;
    }

    private SeenData readSeenFromDisk(long accountHash) {
        SeenData d = readJsonFile(seenFile(accountHash), SeenData.class);
        if (d == null) {
            d = new SeenData();
        }
        if (d.slots == null) {
            d.slots = new HashMap<>();
        }
        return d;
    }

    private static File seenFile(long accountHash) {
        return new File(Persistance.directory, String.format(SEEN_FILE_TEMPLATE, accountHash));
    }

    private <T> T readJsonFile(File file, Type type) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return gson.fromJson(reader, type);
        } catch (FileNotFoundException ignored) {
            return null;
        } catch (JsonSyntaxException | JsonIOException | IOException e) {
            log.warn("error loading json file {}", file, e);
            return null;
        }
    }
}
