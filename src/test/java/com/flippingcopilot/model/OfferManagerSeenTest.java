package com.flippingcopilot.model;

import com.flippingcopilot.controller.DoesNothingExecutorService;
import com.flippingcopilot.controller.Persistance;
import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.util.Map;

import static org.junit.Assert.*;

public class OfferManagerSeenTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private OfferManager manager;

    @Before
    public void setUp() throws Exception {
        Persistance.setUp(tmp.getRoot().getAbsolutePath());
        manager = new OfferManager(new Gson(), new DoesNothingExecutorService());
    }

    private void writeOffer(long hash, int slot, String state) throws Exception {
        writeFile("acc_" + hash + "_" + slot + ".json", "{\"itemId\":26219,\"quantitySold\":0,\"totalQuantity\":2,\"price\":17718861," + "\"spent\":0,\"state\":\"" + state + "\",\"copilotPriceUsed\":true,\"wasCopilotSuggestion\":true}");
    }

    private void writeFile(String name, String content) throws Exception {
        try (FileWriter w = new FileWriter(new File(tmp.getRoot(), name))) {
            w.write(content);
        }
    }

    @Test
    public void stampSeenAllWritesAllEightSlots() {
        manager.stampSeenAll(123L, 2000L);
        Map<Integer, Long> seen = manager.loadSeen(123L);
        for (int slot = 0; slot < 8; slot++) {
            assertEquals(Long.valueOf(2000L), seen.get(slot));
        }
    }

    @Test
    public void stampIsMonotonicPerSlot() {
        manager.stampSeen(123L, 4, 2000L);
        manager.stampSeen(123L, 4, 1000L);
        assertEquals(Long.valueOf(2000L), manager.loadSeen(123L).get(4));
    }

    @Test
    public void loadSeenMaxJoinsDiskAndMemory() throws Exception {

        manager.stampSeen(123L, 2, 1000L);
        writeFile("acc_123_seen.json", "{\"2\":5000}");
        assertEquals(Long.valueOf(5000L), manager.loadSeen(123L).get(2));

        manager.stampSeen(123L, 3, 9000L);
        writeFile("acc_123_seen.json", "{\"3\":100}");
        assertEquals(Long.valueOf(9000L), manager.loadSeen(123L).get(3));
    }

    @Test
    public void stampCacheIsSeededFromDiskSoFlushDoesNotDeleteForeignEntries() throws Exception {

        writeFile("acc_123_seen.json", "{\"7\":4000}");
        manager.stampSeen(123L, 0, 1000L);
        manager.flushSeen();
        OfferManager fresh = new OfferManager(new Gson(), new DoesNothingExecutorService());
        assertEquals(Long.valueOf(4000L), fresh.loadSeen(123L).get(7));
        assertEquals(Long.valueOf(1000L), fresh.loadSeen(123L).get(0));
    }

    @Test
    public void loadSeenReturnsEmptyMapForUnknownAccountAndUnparseableFile() throws Exception {
        assertTrue(manager.loadSeen(999L).isEmpty());
        writeFile("acc_777_seen.json", "{not json");
        assertTrue(manager.loadSeen(777L).isEmpty());
    }


    @Test
    public void returnsRestingOffersJoinedWithSidecar() throws Exception {
        writeOffer(123L, 4, "SELLING");
        manager.stampSeen(123L, 4, 1000L);
        OfferManager.EnumerationResult r = manager.listRestingOffers();
        assertEquals(1, r.resting.size());
        OfferManager.RestingOffer ro = r.resting.get(0);
        assertEquals(123L, ro.accountHash);
        assertEquals(4, ro.slot);
        assertEquals(1000L, ro.lastSeen);
        assertEquals(26219, ro.offer.getItemId());
        assertTrue(r.resolvedSlotKeys.contains("123:4"));
    }

    @Test
    public void skipsRestingOfferWithoutSidecarEntryAndLeavesSlotKeyUnresolved() throws Exception {
        writeOffer(123L, 4, "SELLING");
        OfferManager.EnumerationResult r = manager.listRestingOffers();
        assertTrue(r.resting.isEmpty());
        assertFalse(r.resolvedSlotKeys.contains("123:4"));
    }

    @Test
    public void nonRestingParsedOfferIsResolvedButNotReturned() throws Exception {
        writeOffer(123L, 3, "SOLD");
        OfferManager.EnumerationResult r = manager.listRestingOffers();
        assertTrue(r.resting.isEmpty());
        assertTrue(r.resolvedSlotKeys.contains("123:3"));
    }

    @Test
    public void ignoresNonOfferFilesNegativeOkAndHashMinusOneExcluded() throws Exception {
        writeOffer(-5L, 0, "BUYING");
        manager.stampSeen(-5L, 0, 1000L);
        writeOffer(-1L, 1, "BUYING");
        writeFile("acc_123_seen.json", "{}");
        writeFile("deadbeef_session_data.jsonl", "");
        OfferManager.EnumerationResult r = manager.listRestingOffers();
        assertEquals(1, r.resting.size());
        assertEquals(-5L, r.resting.get(0).accountHash);
    }

    @Test
    public void unparseableOfferFileIsSkippedAndUnresolved() throws Exception {
        writeFile("acc_123_2.json", "{torn");
        writeOffer(123L, 5, "SELLING");
        manager.stampSeen(123L, 5, 1000L);
        OfferManager.EnumerationResult r = manager.listRestingOffers();
        assertEquals(1, r.resting.size());
        assertFalse(r.resolvedSlotKeys.contains("123:2"));
        assertTrue(r.resolvedSlotKeys.contains("123:5"));
    }
}
