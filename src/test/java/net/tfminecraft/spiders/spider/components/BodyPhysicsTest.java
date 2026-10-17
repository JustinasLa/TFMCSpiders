package net.tfminecraft.spiders.spider.components;

import net.tfminecraft.spiders.spider.components.body.Leg;
import net.tfminecraft.spiders.spider.components.body.LegTarget;
import net.tfminecraft.spiders.spider.components.body.SpiderBody;
import net.tfminecraft.spiders.spider.configuration.BodyPlan;
import net.tfminecraft.spiders.spider.configuration.OrientationMode;
import net.tfminecraft.spiders.utilities.maths.Maths;
import org.bukkit.World;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The body's own physics: teleports, leaps, shoves, ground contact and body plans. */
class BodyPhysicsTest {
    private static final int SETTLE_TICKS = 100;

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

    private static World empty() {
        return new Room((x, y, z) -> false).world();
    }

    private static void tick(Run run, int ticks) {
        for (int tick = 0; tick < ticks; tick++) run.app().update();
    }

    /** One body update on its own, without locomotion steering it. */
    private static void bodyTick(Run run) {
        run.body().update(run.app(), run.entity(), run.options());
    }

    private static Run settled(boolean gallop) {
        Run run = spawn(floor(), 1.0, gallop, new Vector(.5, 0.0, .5), false);
        tick(run, SETTLE_TICKS);
        return run;
    }

    private static boolean finite(Vector vector) {
        return Double.isFinite(vector.getX()) && Double.isFinite(vector.getY()) && Double.isFinite(vector.getZ());
    }

    @Test
    @DisplayName("a settled spider stands in stable contact, its legs carrying its weight")
    void aSettledSpiderStandsInStableContact() {
        Run run = settled(false);
        SpiderBody body = run.body();
        assertNotNull(body.getContact());
        assertTrue(body.getContact().isStable());
        assertEquals(Run.groundedLegs(body), body.getContact().getContactPolygon().size());
        // the legs push up against gravity
        assertEquals(run.gait().getGravityAcceleration(), body.getContactAcceleration().getY(), .01);
    }

    @Test
    @DisplayName("teleporting moves the feet with the body")
    void teleportingMovesTheFeetWithTheBody() {
        Run run = settled(false);
        List<Vector> feet = new ArrayList<>();
        for (Leg leg : run.body().getLegs()) feet.add(leg.getEndEffector().clone());
        Vector offset = new Vector(3.0, 1.0, -2.0);
        run.body().teleport(run.body().getPosition().clone().add(offset));
        List<Leg> legs = run.body().getLegs();
        for (int i = 0; i < legs.size(); i++) {
            assertEquals(0.0, feet.get(i).clone().add(offset).distance(legs.get(i).getEndEffector()), 1e-9);
        }
    }

    @Test
    @DisplayName("an updated body plan rebuilds the legs on the next tick")
    void anUpdatedBodyPlanRebuildsTheLegs() {
        Run run = settled(false);
        List<Leg> before = run.body().getLegs();
        tick(run, 1);
        assertTrue(before == run.body().getLegs(), "Legs rebuilt without a new plan");
        run.body().updateBodyPlan();
        tick(run, 1);
        List<Leg> after = run.body().getLegs();
        assertEquals(before.size(), after.size());
        for (int i = 0; i < after.size(); i++) assertNotSame(before.get(i), after.get(i));
    }

    @Test
    @DisplayName("spiders with an odd number of legs walk and gallop")
    void spidersWithAnOddNumberOfLegsWalkAndGallop() {
        for (boolean gallop : new boolean[]{false, true}) {
            Run run = spawn(floor(), 1.0, gallop, new Vector(.5, 0.0, .5), false);
            BodyPlan plan = run.options().getBodyPlan();
            plan.setLegs(new ArrayList<>(plan.getLegs().subList(0, 7)));
            tick(run, SETTLE_TICKS);
            assertEquals(7, run.body().getLegs().size());
            Vector start = run.body().getPosition().clone();
            run.walkForward(200);
            String label = "gallop=" + gallop + " " + run.describe(-1);
            assertTrue(run.body().getPosition().distance(start) > (gallop ? 20.0 : 10.0), label);
            assertTrue(run.up().getY() > .95, label);
            assertTrue(run.body().getPosition().getY() > .5, "Sagged to the floor: " + label);
            // the unpaired hind leg kept up with the body
            Leg unpaired = run.body().getLegs().get(6);
            assertTrue(unpaired.getEndEffector().distance(run.body().getPosition()) < 6.0, "Left behind: " + unpaired.getEndEffector() + " " + label);
        }
    }

    @Test
    @DisplayName("a spider pointing straight up stays finite while it settles")
    void aSpiderPointingStraightUpStaysFinite() {
        Quaternionf noseUp = new Quaternionf().rotationX((float) (-Math.PI / 2));
        Run run = spawn(floor(), 1.0, false, new Vector(.5, 0.0, .5), false, noseUp);
        tick(run, 1);
        assertNotNull(run.body().getContact(), "Setup: no contact on the first tick");
        for (int tick = 0; tick < 60; tick++) {
            run.app().update();
            SpiderBody body = run.body();
            assertTrue(finite(body.getPosition()) && Float.isFinite(body.getOrientation().x) && Float.isFinite(body.getOrientation().w)
                && Float.isFinite(body.getPreferredOrientation().x) && Float.isFinite(body.getPreferredOrientation().w), run.describe(tick));
            if (body.getContact() != null) assertTrue(finite(body.getContact().getSupportAxis()), run.describe(tick));
        }
    }

    @Test
    @DisplayName("a leap from the ground flies free of the legs until it comes down")
    void aLeapFromTheGroundFliesFree() {
        Run run = settled(false);
        double y = run.body().getPosition().getY();
        run.body().leap(new Vector(0.0, .5, 0.0), 10);
        tick(run, 1);
        assertEquals(9, run.body().getLeapTicks());
        assertTrue(run.body().getPosition().getY() > y + .3, "Did not take off: " + run.describe(0));
        for (Leg leg : run.body().getLegs()) assertFalse(leg.getTarget().isGrounded(), "Leg held on mid-leap");
    }

    /** A spider with every leg disabled, its body lying on the floor. */
    private static Run lying() {
        Run run = spawn(floor(), 1.0, false, new Vector(.5, 0.0, .5), false);
        tick(run, 1);
        for (Leg leg : run.body().getLegs()) leg.setDisabled(true);
        tick(run, SETTLE_TICKS);
        assertTrue(run.body().getOnGround(), "Setup: not lying on the floor " + run.describe(-1));
        return run;
    }

    @Test
    @DisplayName("a leap from a body lying on the floor isn't slowed by ground friction")
    void aLeapFromLyingIsNotSlowedByGroundFriction() {
        Run run = lying();
        double x = run.body().getPosition().getX();
        run.body().leap(new Vector(.3, .5, 0.0), 20);
        bodyTick(run);
        assertEquals(.3 * .98, run.body().getPosition().getX() - x, 1e-9);
        assertEquals(19, run.body().getLeapTicks());
    }

    @Test
    @DisplayName("a tilted climber leaping along its surface ends the leap when it touches the floor")
    void aLeapSkimmingToTheFloorEnds() {
        Run run = spawn(floor(), 1.0, false, new Vector(.5, 0.0, .5), true);
        run.gait().setScanOrientation(OrientationMode.SpiderOrientation);
        tick(run, 1);
        for (Leg leg : run.body().getLegs()) leg.setDisabled(true);
        run.gait().setGravityAcceleration(0.0);
        run.gait().setAirDragCoefficient(0.0);
        run.body().getRotationalVelocity().zero();
        run.body().getOrientation().rotationZ((float) Math.asin(.6));
        Vector down = Maths.rotate(Maths.downVector(), run.body().getOrientation());
        // a leap across its own down, sinking towards the floor: it never presses into the surface, so only touching it can end the leap
        Vector across = new Vector(down.getY(), -down.getX(), 0.0);
        assertEquals(0.0, across.dot(down), 0.0);
        Vector landing = new Vector(.5, .0004, .5);
        run.body().teleport(landing.clone().subtract(across));
        run.body().leap(across, 20);
        bodyTick(run);
        assertEquals(0.0, run.body().getPosition().distance(landing), 1e-12);
        assertTrue(run.body().getOnGround(), "Not touching the floor");
        assertEquals(0, run.body().getLeapTicks(), "Leap did not end on touching down");
    }

    @Test
    @DisplayName("a body heading straight into its ground's up still gets a finite contact frame")
    void aBodyHeadingIntoItsGroundUpGetsAFiniteContact() {
        // feet stacked up a wall (the front pair 2 blocks above the hind pair) pitch the ground orientation straight back,
        // so its up points along the body's heading (+z); with no smoothing the pitch is taken as measured
        Run run = spawn(empty(), 1.0, false, new Vector(.5, 0.0, .5), true);
        run.gait().setGravityAcceleration(0.0);
        run.gait().setTopplingAcceleration(0.0);
        run.gait().setPreferredRotationLerpFraction(0f);
        run.body().setPreferredOrientation(new Quaternionf().rotationX((float) (-Math.PI / 2))); // up = -z
        // the legs are set by hand, so there is no body plan to build them from
        when(run.options().getBodyPlan()).thenReturn(null);
        SpiderBody body = run.body();
        // the ground orientation the contact was worked out with: the legs update right after it
        Quaternionf[] contactGround = {null};
        List<Leg> legs = new ArrayList<>();
        for (Vector offset : List.of(new Vector(-1.0, 2.0, -.5), new Vector(1.0, 2.0, -.5), new Vector(-1.0, 0.0, -.5), new Vector(1.0, 0.0, -.5))) {
            Vector foot = body.getPosition().clone().add(offset);
            Leg leg = mock(Leg.class);
            when(leg.getGroundTarget()).thenAnswer(call -> new LegTarget(foot.clone(), true, 0));
            when(leg.getTarget()).thenAnswer(call -> new LegTarget(foot.clone(), true, 0));
            when(leg.getEndEffector()).thenAnswer(call -> foot.clone());
            when(leg.isGrounded()).thenReturn(true);
            doAnswer(call -> contactGround[0] = new Quaternionf(body.getPreferredOrientation())).when(leg).update();
            legs.add(leg);
        }
        body.setLegs(legs);

        bodyTick(run);
        Vector groundUp = Maths.rotate(Maths.upVector(), contactGround[0]);
        Vector forward = body.forwardDirection();
        Vector planarForward = forward.clone().subtract(groundUp.clone().multiply(forward.dot(groundUp)));
        assertTrue(groundUp.getZ() < -.99 && planarForward.lengthSquared() < 1e-8, "Setup: ground up " + groundUp + ", heading " + forward);
        assertNotNull(body.getContact());
        assertTrue(finite(body.getContact().getCenterOfPressure()) && finite(body.getContact().getSupportAxis()) && finite(body.getContactAcceleration()),
            "Contact " + body.getContact().getCenterOfPressure() + " " + body.getContactAcceleration());
        assertTrue(finite(body.getPosition()), run.describe(0));
    }

    @Test
    @DisplayName("a leap over a void counts down instead of landing")
    void aLeapOverAVoidCountsDown() {
        Run run = spawn(empty(), 1.0, false, new Vector(.5, 0.0, .5), false);
        run.body().leap(new Vector(0.0, .1, 0.0), 40);
        for (int tick = 1; tick <= 20; tick++) {
            run.app().update();
            assertEquals(40 - tick, run.body().getLeapTicks());
        }
        assertTrue(run.body().getVelocity().getY() < 0, "Setup: never started falling");
    }

    @Test
    @DisplayName("a shove slides to a stop at a wall, a leap or a straight-down shove isn't stopped")
    void aShoveStopsAtAWallButALeapDoesNot() {
        // a wall at x >= 3 beside the spider, clear of its legs, and nothing to stand on
        World world = new Room((x, y, z) -> x >= 3 && x <= 8 && y >= -10 && y <= 10).world();

        Run shoved = spawn(world, 1.0, false, new Vector(.5, 0.0, .5), false);
        tick(shoved, 1);
        shoved.body().getVelocity().zero();
        shoved.body().shove(new Vector(3.0, 0.0, 0.0));
        tick(shoved, 1);
        assertEquals(3.0 - .25, shoved.body().getPosition().getX(), 1e-6, "Shove not stopped at the wall");

        Run leaping = spawn(world, 1.0, false, new Vector(.5, 0.0, .5), false);
        tick(leaping, 1);
        leaping.body().getVelocity().zero();
        leaping.body().shove(new Vector(3.0, 0.0, 0.0));
        leaping.body().leap(new Vector(3.0, 0.0, 0.0), 20);
        tick(leaping, 1);
        assertEquals(.5 + 3.0 * .98, leaping.body().getPosition().getX(), 1e-6, "A leap was stopped by the wall");

        Run down = spawn(world, 1.0, false, new Vector(.5, 0.0, .5), false);
        tick(down, 1);
        Vector before = down.body().getPosition().clone();
        down.body().shove(new Vector(0.0, -.5, 0.0));
        tick(down, 1);
        assertEquals(before.getX(), down.body().getPosition().getX(), 1e-9);
        assertTrue(down.body().getPosition().getY() < before.getY() - .5, "A downward shove was held back");
    }

    @Test
    @DisplayName("a weightless body resting on the floor with no feet down still counts as on the ground")
    void aWeightlessBodyRestingOnTheFloorIsOnTheGround() {
        Run run = spawn(floor(), 1.0, false, new Vector(.5, 0.0, .5), false);
        tick(run, 1);
        for (Leg leg : run.body().getLegs()) leg.setDisabled(true);
        run.gait().setGravityAcceleration(0.0);

        for (double height : new double[]{.0005, .5}) {
            run.body().getVelocity().zero();
            run.body().teleport(new Vector(.5, height, .5));
            bodyTick(run);
            assertEquals(height, run.body().getPosition().getY(), 0.0, "Moved without any force");
            assertEquals(height < .001, run.body().getOnGround(), "height=" + height);
        }
    }

    @Test
    @DisplayName("a body driven into a ceiling only bounces if it is moving into it")
    void aBodyDrivenIntoACeilingOnlyBouncesIfMovingIntoIt() {
        // a ceiling at y = 5; the climbing body is tilted so its down points along (0.6, -0.8)
        World world = new Room((x, y, z) -> x >= -20 && x <= 20 && z >= -20 && z <= 20 && y >= 5 && y <= 7).world();
        for (boolean rising : new boolean[]{true, false}) {
            Run run = spawn(world, 1.0, false, new Vector(.5, 0.0, .5), true);
            run.gait().setScanOrientation(OrientationMode.SpiderOrientation);
            tick(run, 1);
            for (Leg leg : run.body().getLegs()) leg.setDisabled(true);
            run.gait().setGravityAcceleration(0.0);
            run.body().getOrientation().rotationZ((float) Math.asin(.6));
            run.body().getRotationalVelocity().zero();
            run.body().getVelocity().copy(new Vector(-1.0, rising ? .1 : -.1, 0.0));
            // rising, it starts below the ceiling; sinking, it starts just inside it and ends up there too
            run.body().teleport(new Vector(.5, rising ? 4.95 : 5.15, .5));
            bodyTick(run);

            assertTrue(run.body().getOnGround(), "rising=" + rising + " missed the ceiling");
            assertEquals(5.0, run.body().getPosition().getY(), 1e-6, "rising=" + rising);
            Vector velocity = run.body().getVelocity();
            assertEquals(-.98, velocity.getX(), 1e-9);
            if (rising) assertTrue(velocity.getY() < 0, "Did not bounce off: " + velocity);
            else assertEquals(-.098, velocity.getY(), 1e-9, "Bounced while moving away");
        }
    }

    @Test
    @DisplayName("with no foothold the body is held at its height above the ground below, or where it is")
    void withNoFootholdTheBodyIsHeldAboveTheGround() {
        for (boolean overGround : new boolean[]{true, false}) {
            Run run = settled(false);
            SpiderBody body = run.body();
            // lifted above its standing height: just within scan reach of the floor, or far above it
            body.teleport(body.getPosition().clone().add(new Vector(0.0, overGround ? .15 : 5.0, 0.0)));
            body.getVelocity().zero();
            // the feet still hold on, but none has a foothold
            for (Leg leg : body.getLegs()) leg.setTarget(new LegTarget(leg.getEndEffector().clone(), false, -1));
            double carried = run.gait().getGravityAcceleration() * Run.groundedLegs(body) / body.getLegs().size();
            assertTrue(carried > 0, "Setup: no foot holding on");
            bodyTick(run);
            assertNotNull(body.getContact(), "overGround=" + overGround);
            assertTrue(body.getContact().isStable(), "overGround=" + overGround);
            double push = body.getContactAcceleration().getY();
            // the grounded legs carry their share of the weight; over the floor they also ease the body down to it
            if (overGround) assertTrue(push < carried - .01, "Not eased towards the floor: " + push + " vs " + carried);
            else assertEquals(carried, push, 1e-9, "Height corrected with no ground in reach");
        }
    }
}
