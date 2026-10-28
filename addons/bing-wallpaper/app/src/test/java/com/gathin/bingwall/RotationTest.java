package com.gathin.bingwall;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * The walk through the week, which is remembered by name: what happens as days arrive at the front and old ones
 * are deleted from the back is the whole point of these cases.
 */
public class RotationTest {

    /** newest first, as BingCache lists them */
    private static final List<String> WEEK = Arrays.asList(
            "20261027.jpg", "20261026.jpg", "20261025.jpg", "20261024.jpg",
            "20261023.jpg", "20261022.jpg", "20261021.jpg");

    @Test
    public void nothingRememberedStartsAtTheNewest() {
        assertEquals(0, Rotation.next(WEEK, null));
        assertEquals(0, Rotation.next(WEEK, ""));
    }

    @Test
    public void theNextPictureIsTheOneBelowIt() {
        assertEquals(1, Rotation.next(WEEK, "20261027.jpg"));
        // 20261024 is at index 3; the picture after it is at index 4.
        assertEquals(4, Rotation.next(WEEK, "20261024.jpg"));
    }

    @Test
    public void theOldestWrapsBackToTheNewest() {
        assertEquals(0, Rotation.next(WEEK, "20261021.jpg"));
    }

    @Test
    public void aDayArrivingAtTheFrontDoesNotShiftTheWalk() {
        // The same picture was showing; a new day pushed everything down one place. The name still resolves,
        // which is exactly what a position would have got wrong.
        List<String> nextDay = new ArrayList<>();
        nextDay.add("20261028.jpg");
        nextDay.addAll(WEEK);
        assertEquals(2, Rotation.next(nextDay, "20261027.jpg"));
    }

    @Test
    public void theOldestAgesOutAndTheWalkClosesTheGap() {
        // 20261021 was showing and has been deleted: the circle continues at the newest, which is also what the
        // wrap above answers, so a deletion never strands the rotation.
        List<String> afterPrune = WEEK.subList(0, 6);
        assertEquals(0, Rotation.next(afterPrune, "20261021.jpg"));
    }

    @Test
    public void aRunOfDeletionsStillLandsOnSomethingThatExists() {
        List<String> afterPrune = Arrays.asList("20261027.jpg", "20261026.jpg", "20261025.jpg");
        int at = Rotation.next(afterPrune, "20261021.jpg");
        assertEquals(0, at);
        assertEquals("20261027.jpg", afterPrune.get(at));
    }

    @Test
    public void onePictureStaysOnItself() {
        List<String> single = Arrays.asList("20261027.jpg");
        assertEquals(0, Rotation.next(single, "20261027.jpg"));
    }

    @Test
    public void nothingToShowIsMinusOne() {
        assertEquals(-1, Rotation.next(new ArrayList<>(), "20261027.jpg"));
        assertEquals(-1, Rotation.next(null, null));
    }

    @Test
    public void everyPictureIsReachedInOneCircuit() {
        // What the owner asked for: seven days, each showing once, then round again.
        List<String> seen = new ArrayList<>();
        String last = null;
        for (int i = 0; i < WEEK.size(); i++) {
            int at = Rotation.next(WEEK, last);
            last = WEEK.get(at);
            seen.add(last);
        }
        assertEquals(WEEK, seen);
        assertEquals("20261027.jpg", WEEK.get(Rotation.next(WEEK, last)));
    }
}
