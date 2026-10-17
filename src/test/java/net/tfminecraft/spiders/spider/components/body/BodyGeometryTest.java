package net.tfminecraft.spiders.spider.components.body;

import net.tfminecraft.spiders.spider.configuration.LegPlan;
import net.tfminecraft.spiders.spider.configuration.SegmentPlan;
import net.tfminecraft.spiders.spider.presets.Presets;
import net.tfminecraft.spiders.utilities.ecs.ECSEntity;
import net.tfminecraft.spiders.utilities.inverse_kinematics.IKChain3D;
import net.tfminecraft.spiders.utilities.inverse_kinematics.IKChainPose3D;
import net.tfminecraft.spiders.utilities.inverse_kinematics.IKSolver3D;
import net.tfminecraft.spiders.utilities.inverse_kinematics.IKTarget3D;
import net.tfminecraft.spiders.utilities.maths.Maths;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Orientation helpers, contact and event records, and the leg IK's solve reuse. */
class BodyGeometryTest {
    private static final double EPSILON = 1e-5;

    private static Vector up(Quaternionf orientation) {
        return Maths.rotate(Maths.upVector(), orientation);
    }

    private static Vector forward(Quaternionf orientation) {
        return Maths.rotate(Maths.forwardVector(), orientation);
    }

    private static void assertVector(Vector expected, Vector actual) {
        assertEquals(0.0, expected.distance(actual), EPSILON, "expected " + expected + " but was " + actual);
    }

    @Test
    @DisplayName("an orientation keeps its up and the forward's part along the surface")
    void orientationKeepsUpAndPlanarForward() {
        Quaternionf orientation = SpiderBody.orientationFrom(new Vector(0.0, 0.0, -2.0), new Vector(0.0, 1.0, 1.0));
        assertVector(new Vector(0.0, 0.0, -1.0), up(orientation));
        assertVector(new Vector(0.0, 1.0, 0.0), forward(orientation));
    }

    @Test
    @DisplayName("a forward along the up falls back to the given direction, then to a fixed axis")
    void forwardAlongUpFallsBack() {
        // the fallback, projected onto the surface
        Quaternionf withFallback = SpiderBody.orientationFrom(new Vector(0.0, 1.0, 0.0), new Vector(0.0, 3.0, 0.0), new Vector(1.0, 5.0, 0.0));
        assertVector(new Vector(1.0, 0.0, 0.0), forward(withFallback));

        // no fallback: on the floor the reference is +z
        Quaternionf floor = SpiderBody.orientationFrom(new Vector(0.0, 1.0, 0.0), new Vector(0.0, 1.0, 0.0));
        assertVector(new Vector(0.0, 0.0, 1.0), forward(floor));
        // a fallback along the up is as good as none
        Quaternionf useless = SpiderBody.orientationFrom(new Vector(0.0, 1.0, 0.0), new Vector(0.0, 1.0, 0.0), new Vector(0.0, -2.0, 0.0));
        assertVector(new Vector(0.0, 0.0, 1.0), forward(useless));

        // on a wall the reference is world up
        Quaternionf wall = SpiderBody.orientationFrom(new Vector(1.0, 0.0, 0.0), new Vector(1.0, 0.0, 0.0));
        assertVector(new Vector(1.0, 0.0, 0.0), up(wall));
        assertVector(new Vector(0.0, 1.0, 0.0), forward(wall));
    }

    @Test
    @DisplayName("snapping turns the heading about the up to the nearest increment")
    void snappingTurnsHeadingToTheNearestIncrement() {
        float quarter = (float) (Math.PI / 2);
        // facing 30 degrees from +z towards +x snaps to +z; 60 degrees snaps to +x
        Quaternionf thirty = new Quaternionf().rotationY((float) Math.toRadians(30.0));
        assertVector(new Vector(0.0, 0.0, 1.0), forward(SpiderBody.snapRotationAbout(thirty, new Vector(0.0, 2.0, 0.0), quarter)));
        Quaternionf sixty = new Quaternionf().rotationY((float) Math.toRadians(60.0));
        Quaternionf snapped = SpiderBody.snapRotationAbout(sixty, new Vector(0.0, 1.0, 0.0), quarter);
        assertVector(new Vector(1.0, 0.0, 0.0), forward(snapped));
        assertVector(new Vector(0.0, 1.0, 0.0), up(snapped));

        // on a wall facing +x, headings snap about that normal
        Quaternionf tilted = new Quaternionf().rotationX((float) Math.toRadians(-80.0)); // forward almost straight up
        Quaternionf onWall = SpiderBody.snapRotationAbout(tilted, new Vector(1.0, 0.0, 0.0), quarter);
        assertVector(new Vector(1.0, 0.0, 0.0), up(onWall));
        assertVector(new Vector(0.0, 1.0, 0.0), forward(onWall));
    }

    @Test
    @DisplayName("snapping a heading that points along the axis leaves the orientation alone")
    void snappingAHeadingAlongTheAxisLeavesItAlone() {
        Quaternionf noseUp = new Quaternionf().rotationX((float) (-Math.PI / 2));
        Quaternionf snapped = SpiderBody.snapRotationAbout(noseUp, new Vector(0.0, 1.0, 0.0), (float) (Math.PI / 2));
        assertTrue(snapped.equals(noseUp, 1e-6f));
        assertTrue(snapped != noseUp, "Returns a copy");
    }

    @Test
    @DisplayName("a body's location faces its forward direction")
    void locationFacesForward() {
        World world = mock(World.class);
        UUID uuid = UUID.randomUUID();
        SpiderBody body = SpiderBody.create(world, new Vector(1.0, 2.0, 3.0), new Quaternionf().rotationY((float) (Math.PI / 2)), uuid);
        assertSame(uuid, body.getUuid());
        Location location = body.location();
        assertSame(world, location.getWorld());
        assertVector(new Vector(1.0, 2.0, 3.0), location.toVector());
        assertVector(new Vector(1.0, 0.0, 0.0), location.getDirection());

        // before its first tick it has no contact and no contact force
        assertNull(body.getContact());
        assertTrue(body.getContactAcceleration().isZero());
        assertFalse(body.isRotatingYaw());
        body.setRotatingYaw(true);
        assertTrue(body.isRotatingYaw());
        Quaternionf preferred = new Quaternionf().rotationX(.3f);
        body.setPreferredOrientation(preferred);
        assertSame(preferred, body.getPreferredOrientation());
    }

    @Test
    @DisplayName("a zero rotation impulse leaves the spin unchanged")
    void zeroRotationImpulseLeavesSpinUnchanged() {
        SpiderBody body = SpiderBody.create(mock(World.class), new Vector());
        body.accelerateRotation(new Vector(), 1f);
        assertEquals(0f, body.getRotationalVelocity().length());
        body.accelerateRotation(new Vector(0.0, 2.0, 0.0), .5f);
        assertEquals(.5f, body.getRotationalVelocity().y, 1e-6f);
    }

    @Test
    @DisplayName("contact info keeps what it is given and is stable unless told otherwise")
    void contactInfoDefaults() {
        Vector axis = new Vector(0.0, 1.0, 0.0);
        Vector pressure = new Vector(1.0, 0.0, 0.0);
        List<Vector> polygon = List.of(new Vector(), new Vector(1.0, 0.0, 0.0));
        Vector mass = new Vector(0.0, .5, 0.0);

        ContactInfo full = new ContactInfo(axis, pressure, polygon, mass, false);
        assertSame(axis, full.getSupportAxis());
        assertSame(pressure, full.getCenterOfPressure());
        assertSame(polygon, full.getContactPolygon());
        assertSame(mass, full.getCentreOfMass());
        assertFalse(full.isStable());

        ContactInfo withMass = new ContactInfo(axis, pressure, polygon, mass);
        assertTrue(withMass.isStable());
        assertSame(mass, withMass.getCentreOfMass());
        ContactInfo withPolygon = new ContactInfo(axis, pressure, polygon);
        assertSame(polygon, withPolygon.getContactPolygon());
        assertNull(withPolygon.getCentreOfMass());
        ContactInfo withPressure = new ContactInfo(axis, pressure);
        assertSame(pressure, withPressure.getCenterOfPressure());
        assertNull(withPressure.getContactPolygon());
        ContactInfo axisOnly = new ContactInfo(axis);
        assertSame(axis, axisOnly.getSupportAxis());
        assertNull(axisOnly.getCenterOfPressure());
        assertTrue(axisOnly.isStable());
    }

    @Test
    @DisplayName("a leg IK reports its parts and segment tips once solved")
    void legIkReportsItsPartsAndTips() {
        LegPlan plan = new LegPlan(new Vector(0.0, 0.0, .5), new Vector(0.0, 0.0, 2.0),
            new ArrayList<>(List.of(new SegmentPlan(1.0, Presets.hipJoint()), new SegmentPlan(1.0, Presets.kneeJoint()))),
            new Quaternionf());
        SpiderLegIK ik = SpiderLegIK.createSpiderLegIK(plan);
        assertSame(plan, ik.getPlan());
        assertEquals(2, ik.getChain().getSegments().size());
        assertTrue(ik.getSegmentTipPositions().isEmpty(), "No tips before the first solve");

        Vector body = new Vector(10.0, 5.0, 10.0);
        ik.solve(body, new Quaternionf(), new Vector(10.0, 4.0, 12.0));
        assertVector(new Vector(10.0, 5.0, 10.5), ik.getPose().getPosition());
        List<Vector> tips = ik.getSegmentTipPositions();
        assertEquals(2, tips.size());
        assertEquals(1.0, tips.get(0).distance(ik.getPose().getPosition()), 1e-6);
        assertEquals(1.0, tips.get(1).distance(tips.get(0)), 1e-6);
        assertEquals(2, ik.getSegmentModelRotations().size());
    }

    @Test
    @DisplayName("an IK without a pose keeps solving even when nothing changed")
    void ikWithoutAPoseKeepsSolving() {
        LegPlan plan = new LegPlan(new Vector(), new Vector(), new ArrayList<>(), new Quaternionf());
        IKChain3D chain = mock(IKChain3D.class);
        when(chain.getWorldNodes(any())).thenReturn(List.of());
        IKSolver3D solver = mock(IKSolver3D.class);
        IKChainPose3D pose = new IKChainPose3D(new Vector(), new Quaternionf(), new ArrayList<>());
        SpiderLegIK ik = new SpiderLegIK(plan, chain, pose, solver, new IKTarget3D(new Vector()));

        for (int i = 0; i < 5; i++) ik.solve(new Vector(), new Quaternionf(), new Vector(0.0, -1.0, 0.0));
        verify(solver, times(5)).solve(any(), any(), any());
    }

    @Test
    @DisplayName("events carry the spider and leg they are about")
    void eventsCarryTheirSpiderAndLeg() {
        SpiderBody body = SpiderBody.create(mock(World.class), new Vector());
        assertSame(body, new SpiderBodyHitGroundEvent(body).getSpider());
        Leg leg = mock(Leg.class);
        ECSEntity entity = new ECSEntity();
        LegStepEvent step = new LegStepEvent(entity, body, leg);
        assertSame(entity, step.getEntity());
        assertSame(body, step.getSpider());
        assertSame(leg, step.getLeg());
    }
}
