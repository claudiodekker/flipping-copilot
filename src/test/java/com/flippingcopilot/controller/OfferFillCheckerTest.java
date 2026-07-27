package com.flippingcopilot.controller;

import com.flippingcopilot.model.WikiLatestPrice;
import com.flippingcopilot.model.OfferManager;
import com.flippingcopilot.model.SavedOffer;
import com.google.gson.Gson;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class OfferFillCheckerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final int FANG = 26219;
    private static final int WHIP = 4151;

    private OfferManager offerManager;
    private long now;
    private final List<String> notifications = new ArrayList<>();
    private final Map<Integer, WikiLatestPrice> responses = new HashMap<>();
    private final List<Integer> requested = new ArrayList<>();
    private final List<Consumer<WikiLatestPrice>> pending = new ArrayList<>();
    private int fetches;
    private OfferFillChecker checker;

    @Before
    public void setUp() throws Exception {
        Persistance.setUp(tmp.getRoot().getAbsolutePath());
        offerManager = new OfferManager(new Gson(), new DoesNothingExecutorService());
        now = 100_000L;
        OfferFillChecker.PriceFetcher fetcher = (itemId, consumer) -> {
            fetches++;
            consumer.accept(responses.get(itemId));
        };
        checker = new OfferFillChecker(offerManager, () -> true, () -> now, Runnable::run,
                fetcher, id -> "Osmumten's fang", notifications::add);
    }

    private void writeSellOffer(long hash, int slot, long price) throws Exception {
        writeSellOffer(hash, slot, price, FANG);
    }

    private void writeSellOffer(long hash, int slot, long price, int itemId) throws Exception {
        try (FileWriter w = new FileWriter(new File(tmp.getRoot(), "acc_" + hash + "_" + slot + ".json"))) {
            w.write("{\"itemId\":" + itemId + ",\"quantitySold\":0,\"totalQuantity\":2,\"price\":" + price + ",\"spent\":0,\"state\":\"SELLING\",\"copilotPriceUsed\":true,\"wasCopilotSuggestion\":true}");
        }
        offerManager.stampSeen(hash, slot, 50_000L);
    }

    // holds each consumer instead of calling it, so a test controls when responses land
    private OfferFillChecker deferredChecker() {
        return new OfferFillChecker(offerManager, () -> true, () -> now, Runnable::run,
                (itemId, consumer) -> {
                    requested.add(itemId);
                    pending.add(consumer);
                },
                id -> "Osmumten's fang", notifications::add);
    }

    private Long seen(long hash, int slot) {
        return offerManager.loadSeen(hash).get(slot);
    }

    private static SavedOffer savedOffer(GrandExchangeOfferState state, long price) {
        SavedOffer o = new SavedOffer();
        o.setItemId(FANG);
        o.setTotalQuantity(2);
        o.setPrice(price);
        o.setState(state);
        return o;
    }

    private static WikiLatestPrice latest(Long high, Long highTime, Long low, Long lowTime) {
        return new WikiLatestPrice(high, highTime, low, lowTime);
    }

    @Test
    public void buyFilledByStrictlyLowerInstaSellAfterLastSeen() {
        assertTrue(OfferFillChecker.filled(savedOffer(GrandExchangeOfferState.BUYING, 100), 1000L, latest(999L, 1100L, 99L, 1100L)));
    }

    @Test
    public void equalPriceDoesNotFill() {
        assertFalse(OfferFillChecker.filled(savedOffer(GrandExchangeOfferState.SELLING, 100), 1000L, latest(100L, 1100L, 1L, 1100L)));
        assertFalse(OfferFillChecker.filled(savedOffer(GrandExchangeOfferState.BUYING, 100), 1000L, latest(999L, 1100L, 100L, 1100L)));
    }

    @Test
    public void tradeAtOrBeforeLastSeenDoesNotFill() {
        assertFalse(OfferFillChecker.filled(savedOffer(GrandExchangeOfferState.SELLING, 100), 1100L, latest(101L, 1100L, 1L, 1100L)));
        assertFalse(OfferFillChecker.filled(savedOffer(GrandExchangeOfferState.SELLING, 100), 1100L, latest(101L, 900L, 1L, 900L)));
    }

    @Test
    public void sellIgnoresTheInstaSellSideAndViceVersa() {
        assertFalse(OfferFillChecker.filled(savedOffer(GrandExchangeOfferState.SELLING, 100), 1000L, latest(99L, 1100L, 50L, 1100L)));
        assertFalse(OfferFillChecker.filled(savedOffer(GrandExchangeOfferState.BUYING, 100), 1000L, latest(150L, 1100L, 101L, 1100L)));
    }

    @Test
    public void nullPriceOrNullFieldsDoNotFill() {
        SavedOffer sell = savedOffer(GrandExchangeOfferState.SELLING, 100);
        assertFalse(OfferFillChecker.filled(sell, 1000L, null));
        assertFalse(OfferFillChecker.filled(sell, 1000L, latest(null, 1100L, 1L, 1100L)));
        assertFalse(OfferFillChecker.filled(sell, 1000L, latest(101L, null, 1L, 1100L)));
    }

    @Test
    public void notifiesOnceWhenAnInstaBuyPrintsAboveTheSellPrice() throws Exception {
        writeSellOffer(123L, 4, 100L);
        responses.put(FANG, latest(101L, 60_000L, 1L, 60_000L));
        checker.poll();
        assertEquals("Flipping Copilot: 2 × Osmumten's fang likely sold @ 100", notifications.get(0));

        checker.poll();
        checker.poll();
        assertEquals("notify-once: repeat polls must stay silent", 1, notifications.size());
        assertEquals("a notified offer must stop costing a request every poll", 1, fetches);
    }

    @Test
    public void noNotificationWhenThePrintDoesNotCross() throws Exception {
        writeSellOffer(123L, 4, 100L);
        responses.put(FANG, latest(99L, 60_000L, 1L, 60_000L));
        checker.poll();
        assertTrue(notifications.isEmpty());
    }

    @Test
    public void nullResponseNotifiesNothingAndRetriesNextPoll() throws Exception {
        writeSellOffer(123L, 4, 100L);
        responses.put(FANG, null);
        checker.poll();
        assertTrue(notifications.isEmpty());
        responses.put(FANG, latest(101L, 60_000L, 1L, 60_000L));
        checker.poll();
        assertEquals("a failed fetch must not burn the offer", 1, notifications.size());
    }

    @Test
    public void oneRequestPerItemNotifiesBothAccounts() throws Exception {
        writeSellOffer(123L, 4, 100L);
        writeSellOffer(456L, 2, 100L);
        responses.put(FANG, latest(101L, 60_000L, 1L, 60_000L));
        checker.poll();
        assertEquals(1, fetches);
        assertEquals(2, notifications.size());
    }

    @Test
    public void fingerprintChangeReArmsAndNotifiesAgain() throws Exception {
        writeSellOffer(123L, 4, 100L);
        responses.put(FANG, latest(101L, 60_000L, 1L, 60_000L));
        checker.poll();
        assertEquals(1, notifications.size());

        writeSellOffer(123L, 4, 95L);
        offerManager.stampSeen(123L, 4, 55_000L);
        responses.put(FANG, latest(96L, 61_000L, 1L, 61_000L));
        checker.poll();
        assertEquals("a repriced offer is a new offer", 2, notifications.size());
    }

    @Test
    public void unparseableOfferFileRetainsNotifiedStateSoNoDuplicateAlert() throws Exception {
        writeSellOffer(123L, 4, 100L);
        responses.put(FANG, latest(101L, 60_000L, 1L, 60_000L));
        checker.poll();
        assertEquals(1, notifications.size());

        try (FileWriter w = new FileWriter(new File(tmp.getRoot(), "acc_123_4.json"))) {
            w.write("{ this is a torn write");
        }
        checker.poll();
        writeSellOffer(123L, 4, 100L);
        checker.poll();
        assertEquals("a torn read must not drop the notified watermark", 1, notifications.size());
    }

    @Test
    public void tornReadWhileLoggedIntoTheAccountKeepsTheNotifiedWatermark() throws Exception {
        writeSellOffer(123L, 4, 100L);
        responses.put(FANG, latest(101L, 60_000L, 1L, 60_000L));
        checker.poll();
        assertEquals(1, notifications.size());

        // unsupported world, so logging back out will not stampSeenAll: lastSeen and the fingerprint
        // stay put, and any second alert is a genuine duplicate rather than a legitimate re-arm
        checker.onOsrsLoggedIn(123L, false);
        try (FileWriter w = new FileWriter(new File(tmp.getRoot(), "acc_123_4.json"))) {
            w.write("{ this is a torn write");
        }
        checker.poll();

        writeSellOffer(123L, 4, 100L);
        checker.onOsrsLoginScreen();
        checker.poll();
        assertEquals("an unresolved slot key must not drop the watermark, live account or not",
                1, notifications.size());
    }

    @Test
    public void notifiedWatermarkSurvivesAPollTakenWhileThatAccountIsLoggedIn() throws Exception {
        writeSellOffer(123L, 4, 100L);
        responses.put(FANG, latest(101L, 60_000L, 1L, 60_000L));
        checker.poll();
        assertEquals(1, notifications.size());

        // unsupported world (Leagues, DMM...), so the fingerprint survives logout — see the torn-read test
        checker.onOsrsLoggedIn(123L, false);
        checker.poll();
        checker.onOsrsLoginScreen();
        checker.poll();
        assertEquals("a poll taken while the account is live must not drop its watermark",
                1, notifications.size());
    }

    @Test
    public void aThrowingNotificationDoesNotStrandTheChainForTheSession() throws Exception {
        writeSellOffer(123L, 4, 100L);
        OfferFillChecker throwing = new OfferFillChecker(offerManager, () -> true, () -> now, Runnable::run,
                (itemId, consumer) -> {
                    requested.add(itemId);
                    consumer.accept(latest(101L, 60_000L, 1L, 60_000L));
                },
                id -> "Osmumten's fang", msg -> { throw new IllegalStateException("tray unavailable"); });

        throwing.poll();
        writeSellOffer(123L, 5, 100L, WHIP);
        throwing.poll();
        assertEquals("a failed notification must not wedge chainInFlight for the session", 2, requested.size());
    }

    @Test
    public void requestsAreChainedNotBurst() throws Exception {
        writeSellOffer(123L, 4, 100L);
        writeSellOffer(123L, 5, 100L, WHIP);
        OfferFillChecker chained = deferredChecker();

        chained.poll();
        assertEquals("only one request may be in flight at a time", 1, requested.size());

        pending.get(0).accept(latest(101L, 60_000L, 1L, 60_000L));
        assertEquals("the next request only follows the previous response", 2, requested.size());

        pending.get(1).accept(latest(101L, 60_000L, 1L, 60_000L));
        assertEquals(2, requested.size());
        assertEquals(2, notifications.size());
    }

    @Test
    public void aFailedFetchDoesNotStallTheQueue() throws Exception {
        writeSellOffer(123L, 4, 100L);
        writeSellOffer(123L, 5, 100L, WHIP);
        OfferFillChecker chained = deferredChecker();

        chained.poll();
        assertEquals(1, requested.size());

        pending.get(0).accept(null);
        assertEquals("a failed fetch must not stall the queue", 2, requested.size());

        pending.get(1).accept(latest(101L, 60_000L, 1L, 60_000L));
        assertEquals("the item behind the failure still notifies", 1, notifications.size());
    }

    @Test
    public void aPollDoesNotStartASecondChainWhileOneIsInFlight() throws Exception {
        writeSellOffer(123L, 4, 100L);
        writeSellOffer(123L, 5, 100L, WHIP);
        OfferFillChecker chained = deferredChecker();

        chained.poll();
        assertEquals(1, requested.size());
        chained.poll();
        assertEquals("a poll must not re-request items a pending chain still has in flight", 1, requested.size());

        pending.get(0).accept(null);
        pending.get(1).accept(null);
        chained.poll();
        assertEquals("once the chain drains the next poll runs normally", 3, requested.size());
    }

    @Test
    public void loggedInAccountIsNeitherFetchedNorNotified() throws Exception {
        writeSellOffer(123L, 4, 100L);
        responses.put(FANG, latest(101L, 60_000L, 1L, 60_000L));
        checker.onOsrsLoggedIn(123L, true);
        checker.poll();
        assertTrue(notifications.isEmpty());
        assertEquals(0, fetches);
    }

    @Test
    public void accountIsCheckedAgainOnceItsSessionEnds() throws Exception {
        writeSellOffer(123L, 4, 100L);
        checker.onOsrsLoggedIn(123L, true);
        checker.onOsrsLoginScreen();
        now += 3600;
        responses.put(FANG, latest(101L, 101_000L, 1L, 101_000L));
        checker.poll();
        assertEquals("the account we just logged out of is exactly who we alert about", 1, notifications.size());
    }

    @Test
    public void disabledGateDoesNothing() throws Exception {
        OfferFillChecker off = new OfferFillChecker(offerManager, () -> false, () -> now, Runnable::run,
                (i, c) -> fail("must not fetch"), id -> "Osmumten's fang", notifications::add);
        writeSellOffer(123L, 4, 100L);
        off.poll();
        assertTrue(notifications.isEmpty());
    }

    @Test
    public void loginThenLogoutStampsAllSlots() {
        checker.onOsrsLoggedIn(123L, true);
        now = 105_000L;
        checker.onOsrsLoginScreen();
        assertEquals(Long.valueOf(105_000L), seen(123L, 0));
        assertEquals(Long.valueOf(105_000L), seen(123L, 7));
    }

    @Test
    public void loginScreenReentryWithoutSessionDoesNotStamp() {
        checker.onOsrsLoggedIn(123L, true);
        checker.onOsrsLoginScreen();
        now = 200_000L;
        checker.onOsrsLoginScreen();
        assertTrue(seen(123L, 0) < 200_000L);
    }

    @Test
    public void unsupportedWorldSessionNeverStamps() {
        checker.onOsrsLoggedIn(123L, false);
        now = 105_000L;
        checker.onOsrsLoginScreen();
        assertNull(seen(123L, 0));
    }

    @Test
    public void supportedToUnsupportedHopStampsAtTheHop() {
        checker.onOsrsLoggedIn(123L, true);
        now = 105_000L;
        checker.onOsrsLoggedIn(123L, false);
        assertEquals(Long.valueOf(105_000L), seen(123L, 0));
        now = 110_000L;
        checker.onOsrsLoginScreen();
        assertEquals(Long.valueOf(105_000L), seen(123L, 0));
    }

    @Test
    public void staleCaptureAfterResetDoesNotStampOnDirectUnsupportedLogin() {
        checker.onOsrsLoggedIn(123L, true);
        checker.onOsrsLoginScreen();
        now = 200_000L;
        checker.onOsrsLoggedIn(456L, false);
        assertTrue("stale capture must not stamp across the away window", seen(123L, 0) < 200_000L);
    }

    @Test
    public void shutdownDuringActiveSessionStampsAndFlushes() throws Exception {
        checker.onOsrsLoggedIn(123L, true);
        now = 105_000L;
        checker.onSessionEndShutdown();
        OfferManager fresh = new OfferManager(new Gson(), new DoesNothingExecutorService());
        assertEquals(Long.valueOf(105_000L), fresh.loadSeen(123L).get(0));
    }

    @Test
    public void shutdownAfterLogoutFlushesWithoutRestamping() throws Exception {
        checker.onOsrsLoggedIn(123L, true);
        now = 105_000L;
        checker.onOsrsLoginScreen();
        now = 110_000L;
        checker.onSessionEndShutdown();
        OfferManager fresh = new OfferManager(new Gson(), new DoesNothingExecutorService());
        assertEquals(Long.valueOf(105_000L), fresh.loadSeen(123L).get(0));
    }
}
