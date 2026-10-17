package net.tfminecraft.spiders.spider.components;

import net.tfminecraft.spiders.spider.components.body.Leg;
import net.tfminecraft.spiders.spider.configuration.LegPlan;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static net.tfminecraft.spiders.spider.components.SimulationHarness.spawn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;

/** A still leg's cached ground scan is dropped as soon as anything the scan reads changes. */
class LegScanCacheKeyTest {
    private static final int SETTLE_TICKS = 100;

    @BeforeAll
    static void installBlockDataFactory() {
        BlockDataMocks.install();
    }

    @AfterAll
    static void restoreBukkit() {
        BlockDataMocks.restore();
    }

    /** One {@code tick}; whether {@code leg} cast its main scan ray (from the start of its scan line). */
    private static boolean scans(Runnable tick, World world, Leg leg) {
        clearInvocations(world);
        tick.run();
        Vector start = leg.getScanLine().getPoint1();
        return mockingDetails(world).getInvocations().stream()
            .filter(it -> it.getMethod().getName().equals("rayTraceBlocks"))
            .anyMatch(it -> it.<Location>getArgument(0).toVector().distanceSquared(start) < 1e-12);
    }

    /** Waits for the first leg's periodic rescan, checks the next tick reuses it, then makes {@code change}: did the next tick rescan? */
    private static boolean rescansAfter(World world, Runnable tick, Run run, Consumer<Run> change) {
        Leg leg = run.body().getLegs().get(0);
        boolean refreshed = false;
        for (int i = 0; i < 2 * Leg.SCAN_REFRESH_TICKS && !refreshed; i++) refreshed = scans(tick, world, leg);
        assertTrue(refreshed, "Setup: no periodic rescan");
        assertFalse(scans(tick, world, leg), "Setup: the still leg did not reuse its scan");
        change.accept(run);
        return scans(tick, world, leg);
    }

    /**
     * Settles a standing spider and waits for its first leg's periodic rescan, so the next ticks reuse that scan;
     * then makes {@code change} and returns whether the leg scanned again on the very next tick.
     */
    private static boolean rescansAfter(boolean climbing, Consumer<Run> change) {
        return rescansAfter(climbing, change, run -> { });
    }

    /** As above, then runs {@code check} on the spider after that tick. */
    private static boolean rescansAfter(boolean climbing, Consumer<Run> change, Consumer<Run> check) {
        World world = new Room((x, y, z) -> y < 0 && y >= -3).world();
        Run run = spawn(world, 1.0, false, new Vector(.5, 0.0, .5), climbing);
        for (int tick = 0; tick < SETTLE_TICKS; tick++) run.app().update();
        boolean rescanned = rescansAfter(world, () -> run.app().update(), run, change);
        check.accept(run);
        return rescanned;
    }

    /**
     * A spider hanging still in a void at y = 10: no gravity, no locomotion, so only what the test changes moves.
     * Its legs find nothing, and keep reusing that empty scan.
     */
    private static boolean hangingRescansAfter(Consumer<Run> setup, Consumer<Run> change) {
        World world = new Room((x, y, z) -> false).world();
        Run run = spawn(world, 1.0, false, new Vector(.5, 0.0, .5), false);
        run.gait().setGravityAcceleration(0.0);
        setup.accept(run);
        Runnable tick = () -> run.body().update(run.app(), run.entity(), run.options());
        tick.run();
        run.body().getVelocity().zero();
        run.body().teleport(new Vector(.5, 10.0, .5));
        for (int i = 0; i < 10; i++) tick.run();
        assertTrue(run.body().getVelocity().isZero(), "Setup: still moving");
        return rescansAfter(world, tick, run, change);
    }

    @Test
    @DisplayName("a new body height rescans even when the scan still starts at the same point")
    void aNewBodyHeightRescansFromTheSameStart() {
        // the scan starts 1.6 body heights above the rest position, which sits a body height below the leg's planned rest:
        // height 1.25 with the rest at y = 0 and height 0.625 with it at y = 0.375 both start at 0.75 above the body
        assertFalse(hangingRescansAfter(run -> run.gait().getStationary().setBodyHeight(1.25), run -> { }), "Unchanged");
        Leg[] leg = {null};
        Vector[] before = {null, null};
        assertTrue(hangingRescansAfter(run -> run.gait().getStationary().setBodyHeight(1.25), run -> {
            leg[0] = run.body().getLegs().get(0);
            before[0] = leg[0].getScanLine().getPoint1().clone();
            before[1] = leg[0].getScanLine().getPoint2().clone();
            assertEquals(0.0, leg[0].getLegPlan().getRestPosition().getY());
            run.gait().getStationary().setBodyHeight(.625);
            leg[0].getLegPlan().getRestPosition().setY(.375);
        }));
        assertEquals(before[0], leg[0].getScanLine().getPoint1(), "Setup: the scan start moved");
        assertNotEquals(before[1], leg[0].getScanLine().getPoint2());
    }

    @Test
    @DisplayName("a walking leg whose rest moves under an unchanged look-ahead rescans")
    void aWalkingLegWhoseRestMovesRescans() {
        // the look-ahead sits the trigger radius (times a look-ahead fraction of 1) ahead of the rest position:
        // rest 1.5 ahead with radius 0.5, then rest 1.25 ahead with radius 0.75, both put it 2 blocks ahead of the body
        Consumer<Run> setup = run -> {
            run.body().setWalking(true);
            run.gait().setLegLookAheadFraction(1.0);
            run.gait().getStationary().setTriggerZoneRadius(.5);
            for (LegPlan plan : run.options().getBodyPlan().getLegs()) plan.getRestPosition().setZ(1.5);
        };
        assertFalse(hangingRescansAfter(setup, run -> { }), "Unchanged");
        Leg[] leg = {null};
        Vector[] before = {null, null};
        assertTrue(hangingRescansAfter(setup, run -> {
            leg[0] = run.body().getLegs().get(0);
            before[0] = leg[0].getLookAheadPosition().clone();
            before[1] = leg[0].getRestPosition().clone();
            run.gait().getStationary().setTriggerZoneRadius(.75);
            leg[0].getLegPlan().getRestPosition().setZ(1.25);
        }));
        assertEquals(before[0], leg[0].getLookAheadPosition(), "Setup: the look-ahead moved");
        assertNotEquals(before[1], leg[0].getRestPosition());
    }

    @Test
    @DisplayName("an unchanged standing leg keeps reusing its scan")
    void anUnchangedLegKeepsReusingItsScan() {
        assertFalse(rescansAfter(false, run -> { }));
    }

    @Test
    @DisplayName("turning alternative-ground scans off rescans")
    void turningAlternativeGroundOffRescans() {
        assertTrue(rescansAfter(false, run -> run.gait().setLegScanAlternativeGround(false)));
    }

    @Test
    @DisplayName("a new scan height bias rescans")
    void aNewHeightBiasRescans() {
        assertTrue(rescansAfter(false, run -> run.gait().setLegScanHeightBias(.7)));
    }

    @Test
    @DisplayName("gaining grip rescans")
    void gainingGripRescans() {
        assertTrue(rescansAfter(false, run -> run.gait().setGrip(true)));
    }

    @Test
    @DisplayName("turning the body on the spot rescans")
    void turningOnTheSpotRescans() {
        for (boolean climbing : new boolean[]{false, true}) {
            assertTrue(rescansAfter(climbing, run -> run.body().getOrientation().rotateY(.3f)), "climbing=" + climbing);
        }
    }

    @Test
    @DisplayName("moving a leg's rest position rescans, and the foot follows it")
    void movingTheRestPositionRescans() {
        double[] footZ = {0.0};
        assertTrue(rescansAfter(false, run -> {
            Leg leg = run.body().getLegs().get(0);
            footZ[0] = leg.getGroundTarget().getPosition().getZ();
            leg.getLegPlan().getRestPosition().add(new Vector(0.0, 0.0, .6));
        }, run -> {
            double moved = run.body().getLegs().get(0).getGroundTarget().getPosition().getZ() - footZ[0];
            assertTrue(moved > .3, "Foothold moved " + moved);
        }));
    }

    @Test
    @DisplayName("a new comfort zone radius rescans")
    void aNewComfortRadiusRescans() {
        assertTrue(rescansAfter(false, run -> run.gait().setComfortZoneRadius(run.gait().getComfortZoneRadius() * 1.5)));
    }
}
