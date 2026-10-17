package net.tfminecraft.spiders.spider.components;

import net.tfminecraft.spiders.spider.components.body.Leg;
import net.tfminecraft.spiders.spider.components.body.LegTarget;
import net.tfminecraft.spiders.spider.configuration.BodyPlan;
import net.tfminecraft.spiders.spider.configuration.LegPlan;
import net.tfminecraft.spiders.spider.configuration.OrientationMode;
import net.tfminecraft.spiders.spider.configuration.SegmentPlan;
import net.tfminecraft.spiders.spider.presets.Presets;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static net.tfminecraft.spiders.spider.components.SimulationHarness.spawn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Where a leg finds its foothold: plain and alternative scans, walls, edges and the cases where there is none. */
class LegFootholdTest {
    private static final int SETTLE_TICKS = 100;
    private static final int OBSTRUCTED_TARGET_ID = -2;

    @BeforeAll
    static void installBlockDataFactory() {
        BlockDataMocks.install();
    }

    @AfterAll
    static void restoreBukkit() {
        BlockDataMocks.restore();
    }

    private static World floor() {
        return new Room((x, y, z) -> y < 0 && y >= -3).world();
    }

    private static void tick(Run run, int ticks) {
        for (int tick = 0; tick < ticks; tick++) run.app().update();
    }

    /** A body plan with one leg resting at {@code rest} (body space), whose segments add up to {@code reach}. */
    private static BodyPlan oneLeg(Vector rest, double reach) {
        List<SegmentPlan> segments = new ArrayList<>(List.of(new SegmentPlan(reach / 2, Presets.hipJoint()), new SegmentPlan(reach / 2, Presets.kneeJoint())));
        BodyPlan plan = new BodyPlan();
        plan.setLegs(new ArrayList<>(List.of(new LegPlan(new Vector(), rest, segments, new Quaternionf()))));
        return plan;
    }

    /** Spawns a walking-gait spider whose legs follow {@code plan} from the first tick. */
    private static Run spawnWith(World world, BodyPlan plan, boolean climbing, Vector start) {
        Run run = spawn(world, 1.0, false, start, climbing);
        when(run.options().getBodyPlan()).thenReturn(plan);
        return run;
    }

    @Test
    @DisplayName("without alternative-ground scans a foot lands straight under its scan line")
    void withoutAlternativeGroundAFootLandsUnderItsScanLine() {
        // a one-block step right where the feet would otherwise prefer its edge
        World world = new Room((x, y, z) -> (y < 0 && y >= -3) || (y == 0 && z >= 1 && z <= 3)).world();
        Run run = spawn(world, 1.0, false, new Vector(.5, 0.0, -1.5), false);
        run.gait().setLegScanAlternativeGround(false);
        tick(run, SETTLE_TICKS);
        for (Leg leg : run.body().getLegs()) {
            Vector scanStart = leg.getScanLine().getPoint1();
            Vector foot = leg.getGroundTarget().getPosition();
            assertEquals(scanStart.getX(), foot.getX(), 1e-9, "Off the scan line: " + foot);
            assertEquals(scanStart.getZ(), foot.getZ(), 1e-9, "Off the scan line: " + foot);
            double expectedTop = Math.floor(foot.getZ()) >= 1 && Math.floor(foot.getZ()) <= 3 ? 1.0 : 0.0;
            assertEquals(expectedTop, foot.getY(), 1e-6, "Not on the surface under the scan line: " + foot);
        }
    }

    @Test
    @DisplayName("a disabled leg over a void raises its foot under the body")
    void aDisabledLegOverAVoidRaisesItsFoot() {
        Run run = spawn(new Room((x, y, z) -> false).world(), 1.0, false, new Vector(.5, 0.0, .5), false);
        tick(run, 1);
        Leg leg = run.body().getLegs().get(0);
        leg.setDisabled(true);
        tick(run, 1);
        assertNull(leg.getGroundTarget());
        LegTarget target = leg.getTarget();
        assertFalse(target.isGrounded());
        double raise = run.body().posture(run.gait()).getBodyHeight() * .5;
        Vector expected = leg.getLookAheadPosition().clone().add(new Vector(0.0, raise, 0.0));
        assertEquals(0.0, expected.distance(target.getPosition()), 1e-9, "Raised target " + target.getPosition());
        assertEquals(new Vector(0.0, 1.0, 0.0), leg.getSurfaceUp());
    }

    @Test
    @DisplayName("without grip a foot whose scan hits a wall can't stand on it")
    void withoutGripAFootCantStandOnAWall() {
        // the spider lies with its back to the open air, feet towards a wall whose face is at z = 0 (blocks z < 0),
        // scanning along its own down (-z)
        World world = new Room((x, y, z) -> z < 0 && z >= -3).world();
        Quaternionf facingWall = new Quaternionf().rotationX((float) (Math.PI / 2)); // up = +z
        Run run = spawn(world, 1.0, false, new Vector(.5, 5.0, 0.0), false, facingWall);
        run.gait().setScanOrientation(OrientationMode.SpiderOrientation);
        double height = run.gait().getStationary().getBodyHeight();
        run.body().teleport(new Vector(.5, 5.0, height));
        tick(run, 1);
        int onWall = 0;
        for (Leg leg : run.body().getLegs()) {
            LegTarget ground = leg.getGroundTarget();
            if (ground == null || Math.abs(ground.getPosition().getZ()) > 1e-6) continue;
            onWall++;
            assertFalse(leg.getTarget().isGrounded(), "Stood on the wall at " + ground.getPosition());
        }
        assertTrue(onWall > 0, "Setup: no scan reached the wall");
    }

    @Test
    @DisplayName("a climbing body inside a block still finds the floor")
    void aClimbingBodyInsideABlockStillFindsTheFloor() {
        // a one-block post where the body is
        World world = new Room((x, y, z) -> (y < 0 && y >= -3) || (x == 0 && z == 0 && y >= 0 && y <= 1)).world();
        Run run = spawn(world, 1.0, false, new Vector(.5, 0.0, .5), true);
        tick(run, 1);
        for (Leg leg : run.body().getLegs()) {
            LegTarget ground = leg.getGroundTarget();
            assertNotNull(ground, "No foothold");
            assertNotEquals(OBSTRUCTED_TARGET_ID, ground.getId(), "Grabbed the post the body is inside");
        }
    }

    @Test
    @DisplayName("a climbing leg ignores a wall it can't reach")
    void aClimbingLegIgnoresAWallItCantReach() {
        // the leg rests 3 blocks to the side behind a wall at x = 1, but is only 0.2 long
        World world = new Room((x, y, z) -> (y < 0 && y >= -3) || (x >= 1 && x <= 5 && y >= 0 && y <= 3)).world();
        Run run = spawnWith(world, oneLeg(new Vector(3.0, 0.0, 0.0), .2), true, new Vector(.5, 0.0, .5));
        tick(run, 1);
        Leg leg = run.body().getLegs().get(0);
        LegTarget ground = leg.getGroundTarget();
        assertTrue(ground == null || ground.getId() != OBSTRUCTED_TARGET_ID, "Reached the wall: " + ground);
        assertFalse(leg.getTarget().isGrounded());
    }

    @Test
    @DisplayName("a climbing leg resting at the body centre finds no foothold and casts no zero-length rays")
    void aLegRestingAtTheBodyCentreFindsNoFoothold() {
        double height = 1.1; // the walk gait's body height at scale 1
        World world = floor();
        Run run = spawnWith(world, oneLeg(new Vector(0.0, height, 0.0), 2.0), true, new Vector(.5, 0.0, .5));
        assertEquals(height, run.gait().getStationary().getBodyHeight());
        tick(run, 1); // before the falling body brings the scan down to the floor
        Leg leg = run.body().getLegs().get(0);
        assertEquals(0.0, leg.getLookAheadPosition().distance(run.body().getPosition()), 0.0);
        // the scan from there stops short of the floor, and the edge scan back to the body has no length
        assertNull(leg.getGroundTarget());
        assertFalse(leg.getTarget().isGrounded());
    }

    @Test
    @DisplayName("a hit without a block face is not taken as a wall to grab")
    void aHitWithoutAFaceIsNotAWall() {
        World world = mock(World.class);
        Block air = mock(Block.class);
        when(air.isPassable()).thenReturn(true);
        when(world.getBlockAt(any(Location.class))).thenReturn(air);
        // every ray stops halfway, with no face (as an entity-free block ray never does in game)
        when(world.rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(), eq(FluidCollisionMode.NEVER), eq(true))).thenAnswer(call -> {
            Vector start = call.<Location>getArgument(0).toVector();
            Vector direction = call.<Vector>getArgument(1).clone().normalize();
            return new RayTraceResult(start.add(direction.multiply(call.<Double>getArgument(2) / 2)));
        });
        Run run = spawn(world, 1.0, false, new Vector(.5, 0.0, .5), true);
        tick(run, 1);
        for (Leg leg : run.body().getLegs()) {
            LegTarget ground = leg.getGroundTarget();
            assertNotNull(ground);
            assertTrue(ground.getId() > 0, "Not a scan foothold: " + ground.getId());
        }
    }

    @Test
    @DisplayName("a climbing foot settled on an awkward foothold is not uncomfortable")
    void aSettledClimbingFootIsNotUncomfortable() {
        for (boolean climbing : new boolean[]{false, true}) {
            Run run = spawn(floor(), 1.0, false, new Vector(.5, 0.0, .5), climbing);
            tick(run, SETTLE_TICKS);
            Leg leg = run.body().getLegs().get(0);
            assertFalse(leg.isUncomfortable(), "Settled foot");

            // a grounded foothold well outside the comfort zone, with the foot already on it
            Vector far = leg.getRestPosition().clone().add(new Vector(0.0, 0.0, 5.0));
            LegTarget foothold = new LegTarget(far, true, 0);
            leg.setTarget(foothold);
            assertSame(foothold, leg.getTarget());
            leg.setEndEffector(far.clone());
            assertTrue(leg.getEndEffector().equals(far));
            assertEquals(!climbing, leg.isUncomfortable(), "climbing=" + climbing + " at its foothold");

            // not there yet, or with nothing to stand on, it is
            leg.setEndEffector(far.clone().add(new Vector(0.0, 0.0, .5)));
            assertTrue(leg.isUncomfortable(), "climbing=" + climbing + " short of its foothold");
            leg.setEndEffector(far.clone());
            leg.setTarget(new LegTarget(far, false, -1));
            assertTrue(leg.isUncomfortable(), "climbing=" + climbing + " stranded");
        }
    }

    @Test
    @DisplayName("a moving climbing foot outside its comfort zone is uncomfortable")
    void aMovingClimbingFootIsUncomfortable() {
        Run run = spawn(floor(), 1.0, false, new Vector(.5, 0.0, .5), true);
        tick(run, SETTLE_TICKS);
        Leg[] stepping = {null};
        run.walkForward(200, tick -> {
            for (Leg leg : run.body().getLegs()) if (leg.isMoving()) stepping[0] = leg;
            return stepping[0] != null;
        });
        Leg leg = stepping[0];
        assertNotNull(leg, "Setup: no foot stepped");
        // mid-step, even right on a grounded foothold outside the comfort zone
        Vector far = leg.getRestPosition().clone().add(new Vector(0.0, 0.0, 5.0));
        leg.setTarget(new LegTarget(far, true, 0));
        leg.setEndEffector(far.clone());
        assertTrue(leg.isUncomfortable(), "A stepping foot can't be settled");
    }

    @Test
    @DisplayName("a leg reports its spider, plan, zones and last foot position")
    void aLegReportsItsPartsAndHistory() {
        Run run = spawn(floor(), 1.0, false, new Vector(.5, 0.0, .5), false);
        tick(run, SETTLE_TICKS);
        List<Leg> legs = run.body().getLegs();
        for (int i = 0; i < legs.size(); i++) {
            Leg leg = legs.get(i);
            assertSame(run.app(), leg.getEcs());
            assertSame(run.entity(), leg.getEntity());
            assertSame(run.options().getBodyPlan().getLegs().get(i), leg.getLegPlan());
            assertTrue(leg.getTriggerZone().contains(leg.getRestPosition()));
            assertTrue(leg.getComfortZone().getRadius() > leg.getTriggerZone().getRadius());
        }

        // the previous foot position is the one before this tick's step
        run.walkForward(30);
        Leg leg = legs.get(0);
        Vector before = leg.getEndEffector().clone();
        leg.setPreviousEndEffector(new Vector());
        run.walkForward(1);
        assertEquals(before, leg.getPreviousEndEffector());

        // whether it may step is decided by the gait each tick it isn't stepping
        Leg still = null;
        for (int tick = 0; tick < 40 && still == null; tick++) {
            run.app().update();
            for (Leg it : legs) if (!it.isMoving() && it.isGrounded()) still = it;
        }
        assertNotNull(still);
        still.setCanMove(true);
        assertTrue(still.getCanMove());
        run.app().update();
        assertEquals(still.isMoving(), still.getCanMove(), "A still leg starts stepping exactly when its gait lets it");
    }
}
