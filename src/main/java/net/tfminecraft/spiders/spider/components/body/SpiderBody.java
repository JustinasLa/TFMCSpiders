package net.tfminecraft.spiders.spider.components.body;

import net.tfminecraft.spiders.spider.configuration.BodyPlan;
import net.tfminecraft.spiders.spider.configuration.Gait;
import net.tfminecraft.spiders.spider.configuration.LegPlan;
import net.tfminecraft.spiders.spider.configuration.Posture;
import net.tfminecraft.spiders.spider.configuration.SpiderOptions;
import net.tfminecraft.spiders.utilities.CollisionResult;
import net.tfminecraft.spiders.utilities.Geometry;
import net.tfminecraft.spiders.utilities.Polygons;
import net.tfminecraft.spiders.utilities.Triple;
import net.tfminecraft.spiders.utilities.ecs.ECS;
import net.tfminecraft.spiders.utilities.ecs.ECSComponent;
import net.tfminecraft.spiders.utilities.ecs.ECSEntity;
import net.tfminecraft.spiders.utilities.maths.Maths;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector2d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SpiderBody implements ECSComponent {
    private final UUID uuid;
    private final World world;
    private final Vector position;
    private final Quaternionf orientation;

    private boolean onGround = false;
    private List<Leg> legs = List.of();
    private ContactInfo contact = null;
    private Vector contactAcceleration = new Vector(0.0, 0.0, 0.0);

    // state
    private boolean isWalking = false;
    private boolean isRotatingYaw = false;

    /** Ticks left in a leap: the body flies freely, legs reach out instead of holding on. */
    private int leapTicks = 0;

    /** Ticks left in which a shove (hit, wind charge) is stopped by walls. */
    private int shoveTicks = 0;
    private static final int SHOVE_TICKS = 20;

    private BodyPlan lastAppliedBodyPlan = null;

    // memo
    private Quaternionf preferredOrientation;

    private final Vector velocity;
    /** World-space angular velocity (axis × rate, rad/tick). */
    private final Vector3f rotationalVelocity;

    // for lerping
    private float preferredPitch;
    private float preferredRoll;

    public SpiderBody(UUID uuid, World world, Vector position, Quaternionf orientation) {
        this.uuid = uuid;
        this.world = world;
        this.position = position;
        this.orientation = orientation;

        this.preferredOrientation = new Quaternionf(orientation);
        this.velocity = new Vector(0.0, 0.0, 0.0);
        this.rotationalVelocity = new Vector3f();
        this.preferredPitch = orientation.getEulerAnglesYXZ(new Vector3f()).x;
        this.preferredRoll = orientation.getEulerAnglesYXZ(new Vector3f()).z;
    }

    public UUID getUuid() {
        return uuid;
    }

    public World getWorld() {
        return world;
    }

    public Vector getPosition() {
        return position;
    }

    public Quaternionf getOrientation() {
        return orientation;
    }

    public boolean getOnGround() {
        return onGround;
    }

    public List<Leg> getLegs() {
        return legs;
    }

    public void setLegs(List<Leg> legs) {
        this.legs = legs;
    }

    public ContactInfo getContact() {
        return contact;
    }

    public Vector getContactAcceleration() {
        return contactAcceleration;
    }

    public boolean isWalking() {
        return isWalking;
    }

    public void setWalking(boolean walking) {
        this.isWalking = walking;
    }

    public boolean isRotatingYaw() {
        return isRotatingYaw;
    }

    public void setRotatingYaw(boolean rotatingYaw) {
        this.isRotatingYaw = rotatingYaw;
    }

    /** Ticks left in a leap: the body flies freely, legs reach out instead of holding on. */
    public int getLeapTicks() {
        return leapTicks;
    }

    public Quaternionf getPreferredOrientation() {
        return preferredOrientation;
    }

    public void setPreferredOrientation(Quaternionf preferredOrientation) {
        this.preferredOrientation = preferredOrientation;
    }

    public Vector getVelocity() {
        return velocity;
    }

    /** World-space angular velocity (axis × rate, rad/tick). */
    public Vector3f getRotationalVelocity() {
        return rotationalVelocity;
    }

    /** Knocks the body with {@code impulse}; for a second it slides to a stop at walls instead of through them. */
    public void shove(Vector impulse) {
        velocity.add(impulse);
        shoveTicks = SHOVE_TICKS;
    }

    public void leap(Vector launchVelocity, int ticks) {
        velocity.copy(launchVelocity);
        leapTicks = ticks;
    }

    public void updateBodyPlan() {
        lastAppliedBodyPlan = null;
    }

    public Posture posture(Gait gait) {
        if (isRotatingYaw) {
            return gait.getMoving().clone();
        }

        double speedFraction = velocity.length() / gait.getMaxSpeed();
        return gait.getStationary().clone().lerp(gait.getMoving(), speedFraction);
    }

    public static SpiderBody create(World world, Vector position, Quaternionf orientation, UUID uuid) {
        return new SpiderBody(uuid, world, position.clone(), new Quaternionf(orientation));
    }

    public static SpiderBody create(World world, Vector position, Quaternionf orientation) {
        return create(world, position, orientation, UUID.randomUUID());
    }

    public static SpiderBody create(World world, Vector position) {
        return create(world, position, new Quaternionf(), UUID.randomUUID());
    }

    public static Quaternionf orientationFrom(Vector up, Vector forward, Vector fallback) {
        Vector u = up.clone().normalize();
        Vector f = forward.clone().subtract(u.clone().multiply(forward.dot(u)));
        if (f.lengthSquared() < 1e-8) f.copy(referenceAxis(u, fallback));
        f.normalize();

        Vector right = u.clone().crossProduct(f); // right = up × forward
        Matrix3f basis = new Matrix3f().set(right.toVector3f(), u.toVector3f(), f.toVector3f());
        return new Quaternionf().setFromNormalized(basis);
    }

    public static Quaternionf orientationFrom(Vector up, Vector forward) {
        return orientationFrom(up, forward, null);
    }

    private static Vector referenceAxis(Vector up, Vector preferred) {
        if (preferred != null) {
            Vector projected = preferred.clone().subtract(up.clone().multiply(preferred.dot(up)));
            if (projected.lengthSquared() >= 1e-8) return projected.normalize();
        }
        Vector basis = Math.abs(up.getY()) < 0.9 ? Maths.upVector() : Maths.forwardVector();
        return basis.clone().subtract(up.clone().multiply(basis.dot(up))).normalize();
    }

    private static Vector referenceAxis(Vector up) {
        return referenceAxis(up, null);
    }

    public static Quaternionf snapRotationAbout(Quaternionf orientation, Vector up, float incrementRadians) {
        Vector axis = up.clone().normalize();
        Vector reference = referenceAxis(axis);
        Vector forward = Maths.rotate(Maths.forwardVector().clone(), orientation);
        Vector planarForward = forward.clone().subtract(axis.clone().multiply(forward.dot(axis)));
        if (planarForward.lengthSquared() < 1e-8) return new Quaternionf(orientation);

        planarForward.normalize();
        double angle = Math.atan2(axis.dot(reference.clone().crossProduct(planarForward)), reference.dot(planarForward));
        float snapped = Maths.roundToInt(angle / incrementRadians) * incrementRadians;
        return orientationFrom(axis, reference.rotateAroundAxis(axis, (double) snapped));
    }

    // utils
    public Location location() {
        Location location = position.toLocation(world);
        location.setDirection(forwardDirection());
        return location;
    }

    public Vector forwardDirection() {
        return Maths.rotate(Maths.forwardVector(), orientation);
    }

    public void accelerateRotation(Vector axis, float angle) {
        Vector3f a = axis.toVector3f();
        if (a.lengthSquared() < 1e-12f) return;
        a.normalize().mul(angle);
        rotationalVelocity.add(a);
    }

    /** Moves the body and its feet by the same offset. */
    public void teleport(Vector newPosition) {
        Vector diff = newPosition.clone().subtract(position);

        position.copy(newPosition);

        for (Leg leg : legs) leg.getEndEffector().add(diff);
    }

    private static Vector getPos(Leg leg) {
        LegTarget groundTarget = leg.getGroundTarget();
        return groundTarget != null ? groundTarget.getPosition() : leg.getRestPosition();
    }

    private void updatePreferredAngles(Gait gait) {
        Vector up = Maths.rotate(Maths.upVector(), gait.getScanOrientation().get(this));

        // the front pair and the last two legs set the pitch, so it takes at least two legs
        if (legs.size() < 2) return;
        Vector frontLeft = getPos(legs.get(0));
        Vector frontRight = getPos(legs.get(1));
        Vector backLeft = getPos(legs.get(legs.size() - 2));
        Vector backRight = getPos(legs.get(legs.size() - 1));

        Vector forwardLeft = frontLeft.clone().subtract(backLeft);
        Vector forwardRight = frontRight.clone().subtract(backRight);
        Vector forward = Geometry.average(List.of(forwardLeft, forwardRight));

        // left/right pairs set the roll; an unpaired last leg (odd leg count) is left out
        Vector sideways = new Vector(0.0, 0.0, 0.0);
        for (int i = 0; i + 1 < legs.size(); i += 2) {
            sideways.add(getPos(legs.get(i + 1)).clone().subtract(getPos(legs.get(i))));
        }

        Vector bodyForward = forwardDirection();
        Vector headingForward = bodyForward.clone().subtract(up.clone().multiply(bodyForward.dot(up)));
        Quaternionf heading;
        if (headingForward.lengthSquared() < 1e-8) {
            heading = new Quaternionf(orientation);
        } else {
            heading = orientationFrom(up, headingForward);
        }

        Quaternionf inverseHeading = new Quaternionf(heading).invert();
        Vector forwardLocal = Maths.rotate(forward.clone(), inverseHeading);
        Vector sidewaysLocal = Maths.rotate(sideways.clone(), inverseHeading);

        preferredPitch = Maths.lerp(Maths.pitch(forwardLocal), preferredPitch, gait.getPreferredRotationLerpFraction());
        preferredRoll = Maths.lerp(Maths.pitch(sidewaysLocal), preferredRoll, gait.getPreferredRotationLerpFraction());

        preferredOrientation = new Quaternionf(heading).rotateX(preferredPitch).rotateZ(preferredRoll);
    }

    public void update(ECS ecs, ECSEntity entity, SpiderOptions options) {
        Gait gait = options.getGait();

        if (lastAppliedBodyPlan != options.getBodyPlan()) {
            List<Leg> newLegs = new ArrayList<>();
            for (LegPlan it : options.getBodyPlan().getLegs()) newLegs.add(new Leg(ecs, entity, this, it, options));
            legs = newLegs;
            lastAppliedBodyPlan = options.getBodyPlan();
        }

        updatePreferredAngles(gait);

        Vector upAxis = Maths.rotate(Maths.upVector(), preferredOrientation);

        int groundedLegs = 0;
        for (Leg it : legs) if (it.isGrounded()) groundedLegs++;
        double fractionOfLegsGrounded = (double) groundedLegs / legs.size();

        Vector acceleration = new Vector(0.0, -gait.getGravityAcceleration(), 0.0);

        // apply rotational velocity
        float angularSpeed = rotationalVelocity.length();
        if (angularSpeed > 1e-8f) {
            orientation.premul(
                new Quaternionf().rotateAxis(
                    angularSpeed,
                    rotationalVelocity.x / angularSpeed,
                    rotationalVelocity.y / angularSpeed,
                    rotationalVelocity.z / angularSpeed
                )
            );
        }

        boolean leaping = leapTicks > 0;
        ContactInfo contact = leaping ? null : calcContact(gait);
        this.contact = contact;

        // toppling
        if (contact != null && !contact.isStable()) {
            Vector lever = contact.getCentreOfMass().clone().subtract(contact.getCenterOfPressure());
            Vector gravityDir = new Vector(0.0, -1.0, 0.0);
            Vector horizontalLever = lever.clone().subtract(gravityDir.clone().multiply(lever.dot(gravityDir)));
            double overhang = horizontalLever.length();

            if (overhang > 1e-6) {
                // axis = horizontal lever × down: rotating about it drops the centre of mass
                Vector axis = horizontalLever.crossProduct(gravityDir).normalize();
                double angularAcceleration = gait.getTopplingAcceleration() * overhang;
                rotationalVelocity.add(axis.toVector3f().mul((float) angularAcceleration));
            }
        }

        contactAcceleration = new Vector(0.0, 0.0, 0.0);
        if (contact != null) {
            Vector preferredPosition = calcPreferredPosition(gait);

            Vector n = contact.getSupportAxis().clone().normalize();
            double gripLimit =
                !gait.getGrip() ? 0.0 :
                gait.getGripAcceleration() * fractionOfLegsGrounded;

            // decompose gravity into normal and tangent
            Vector gravity = new Vector(0.0, -gait.getGravityAcceleration(), 0.0);
            double gravityIntoSurface = gravity.dot(n);
            Vector gravityTangent = gravity.clone().subtract(n.clone().multiply(gravityIntoSurface));

            // displacement from the preferred height
            double heightError = preferredPosition.clone().subtract(position).dot(n);
            double heightRate = velocity.dot(n);
            double springLimit = gait.getBodyHeightCorrectionAcceleration() * fractionOfLegsGrounded;
            double heightSpring = Maths.clamp(heightError - heightRate, -springLimit, springLimit);

            // push +n
            double support = Math.max(0.0, -gravityIntoSurface * fractionOfLegsGrounded + heightSpring);

            // pull -n
            boolean gravityPressesIn = gravityIntoSurface < 0.0;
            double adhesionForce =
                (gravityPressesIn ? 0.0 : Math.min(0.0, heightSpring)) -
                Math.max(0.0, gravityIntoSurface);
            double adhesion = Maths.clamp(adhesionForce, -gripLimit, 0.0);

            // cancel tangential gravity
            Vector traction = gravityTangent.multiply(-1.0);
            if (traction.lengthSquared() > gripLimit * gripLimit) {
                traction.normalize().multiply(gripLimit);
            }

            contactAcceleration = n.clone().multiply(support + adhesion).add(traction);
            acceleration.add(contactAcceleration);
        }

        // apply the accumulated forces
        velocity.add(acceleration);

        // drag
        if (!isWalking && !leaping) {
            double legDrag = 1 - gait.getGroundDragCoefficient() * fractionOfLegsGrounded;
            Geometry.scaleTangential(velocity, upAxis, legDrag);
        }

        if (onGround && !leaping) {
            Geometry.scaleTangential(velocity, upAxis, .5);
        }

        velocity.multiply(1 - gait.getAirDragCoefficient());

        // rotational drag
        rotationalVelocity.mul(1 - gait.getRotationalDragCoefficient() * (float) fractionOfLegsGrounded);
        if (onGround) rotationalVelocity.mul(.5f);

        // resolve collision
        Quaternionf scanOrientation = gait.getScanOrientation().get(this);
        Vector down = Maths.rotate(Maths.downVector(), scanOrientation);

        // a shoved spider stops at walls instead of passing through them
        if (shoveTicks > 0 && !leaping) stopAtWalls(down);

        // apply velocity
        position.add(velocity);

        CollisionResult collision = Geometry.resolveCollision(world, position, down.clone().multiply(velocity.dot(down)));
        if (collision != null) {
            onGround = true;

            boolean didHit = collision.getOffset().length() > (gait.getGravityAcceleration() * 2) * (1 - gait.getAirDragCoefficient());
            if (didHit) ecs.emit(new SpiderBodyHitGroundEvent(this));

            position.copy(collision.getPosition());

            Vector n = collision.getNormal();
            double intoSurface = velocity.dot(n);
            if (intoSurface < 0) velocity.subtract(n.clone().multiply(intoSurface * (1 + gait.getBounceFactor())));
        } else {
            onGround = Geometry.raycastGround(world, position, down, 0.001) != null;
        }

        // coming down onto ground: the legs take the weight again
        if (leaping && velocity.getY() < 0) {
            boolean nearGround = Geometry.raycastGround(world, position, Maths.downVector(), posture(gait).getBodyHeight()) != null;
            if (onGround || nearGround) leapTicks = 0;
        }

        List<Leg> updateOrder = gait.getType().getLegsInUpdateOrder(this);
        for (Leg leg : updateOrder) leg.updateMemo();
        for (Leg leg : updateOrder) leg.update();

        updatePreferredAngles(gait);

        if (leapTicks > 0) leapTicks--;
        if (shoveTicks > 0) shoveTicks--;
    }

    /** Room the body keeps from a wall it is pushed against. */
    private static final double WALL_MARGIN = .25;

    /** Cuts the motion along the surface short of the first block in the way. */
    private void stopAtWalls(Vector down) {
        Vector along = velocity.clone().subtract(down.clone().multiply(velocity.dot(down)));
        double length = along.length();
        if (length < 1e-6) return;
        RayTraceResult hit = world.rayTraceBlocks(position.toLocation(world), along, length + WALL_MARGIN, FluidCollisionMode.NEVER, true);
        if (hit == null) return;
        double allowed = Math.max(0.0, hit.getHitPosition().distance(position) - WALL_MARGIN);
        velocity.subtract(along.multiply(1 - allowed / length));
    }

    private List<Integer> legsInPolygonalOrder() {
        List<Integer> lefts = new ArrayList<>();
        List<Integer> rights = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) if (LegLookUp.isLeftLeg(i)) lefts.add(i);
        for (int i = 0; i < legs.size(); i++) if (LegLookUp.isRightLeg(i)) rights.add(i);
        List<Integer> out = new ArrayList<>(lefts);
        for (int i = rights.size() - 1; i >= 0; i--) out.add(rights.get(i));
        return out;
    }

    private Vector calcPreferredPosition(Gait gait) {
        Vector up = Maths.rotate(Maths.upVector(), preferredOrientation);
        double bodyHeight = posture(gait).getBodyHeight();

        // feet position
        List<Vector> groundedTargets = new ArrayList<>();
        for (Leg it : legs) if (it.getTarget().isGrounded()) groundedTargets.add(it.getTarget().getPosition());
        Vector feetSupport = groundedTargets.isEmpty() ? null : Geometry.average(groundedTargets);

        // ground position
        Vector lookAhead = position.clone().add(velocity);
        double scanReach = bodyHeight + gait.getMaxBodyDistanceFromGround();
        Vector scanDirection = up.clone().multiply(-1.0);
        RayTraceResult scanHit = Geometry.raycastGround(world, lookAhead, scanDirection, scanReach);
        Vector scanSupport = scanHit != null ? scanHit.getHitPosition() : null;

        // whichever is higher (feet or ground)
        Vector support = null;
        double supportHeight = 0.0;
        for (Vector it : new Vector[]{feetSupport, scanSupport}) {
            if (it == null) continue;
            double height = it.dot(up);
            if (support == null || Double.compare(supportHeight, height) < 0) {
                support = it;
                supportHeight = height;
            }
        }
        if (support == null) return position.clone();

        // keep up component only
        double heightOffset = support.clone().subtract(position).dot(up);
        Vector target = position.clone().add(up.clone().multiply(heightOffset + bodyHeight));
        Vector out = Maths.lerp(position.clone(), target, gait.getBodyHeightCorrectionFactor());
        return out;
    }

    private ContactInfo calcContact(Gait gait) {
        Vector up = Maths.rotate(Maths.upVector(), gait.getScanOrientation().get(this)).normalize();

        List<Vector> endEffectors = new ArrayList<>();
        for (Leg it : legs) endEffectors.add(it.getEndEffector());
        Vector centreOfMass = Geometry.average(endEffectors);
        Maths.lerp(centreOfMass, position, 0.5);
        centreOfMass.add(up.clone().multiply(0.01));

        List<Leg> groundedLegs = new ArrayList<>();
        for (int it : legsInPolygonalOrder()) {
            Leg leg = legs.get(it);
            if (leg.isGrounded()) groundedLegs.add(leg);
        }
        if (groundedLegs.isEmpty()) return null;

        List<Vector> legsPolygon = new ArrayList<>();
        for (Leg it : groundedLegs) legsPolygon.add(it.getEndEffector().clone());

        if (legsPolygon.size() == 1) {
            Vector centerOfPressure = groundedLegs.get(0).getEndEffector().clone();
            return new ContactInfo(
                centreOfMass.clone().subtract(centerOfPressure).normalize(),
                centerOfPressure,
                legsPolygon,
                centreOfMass,
                false
            );
        }

        Vector forward = forwardDirection();
        forward.subtract(up.clone().multiply(forward.dot(up)));
        if (forward.lengthSquared() < 1e-8) {
            forward.copy(Math.abs(up.getY()) < 0.9 ? Maths.upVector() : Maths.forwardVector());
            forward.subtract(up.clone().multiply(forward.dot(up)));
        }
        forward.normalize();
        Vector right = up.clone().crossProduct(forward);

        double alongUpSum = 0.0;
        for (Vector it : legsPolygon) alongUpSum += it.dot(up);
        double polygonAlongUp = alongUpSum / legsPolygon.size();

        List<Vector2d> polygon2D = new ArrayList<>();
        for (Vector it : legsPolygon) polygon2D.add(project(it, right, forward));
        Vector2d centre2D = project(centreOfMass, right, forward);

        if (Polygons.pointInPolygon(centre2D, polygon2D)) return new ContactInfo(
            up.clone(),
            unProject(centre2D, right, forward, up, polygonAlongUp),
            legsPolygon,
            centreOfMass,
            true
        );

        Vector2d nearest = Polygons.nearestPointInPolygon(centre2D, polygon2D);
        Vector centerOfPressure = unProject(nearest, right, forward, up, polygonAlongUp);
        return new ContactInfo(
            centreOfMass.clone().subtract(centerOfPressure).normalize(),
            centerOfPressure,
            legsPolygon,
            centreOfMass,
            false
        );
    }

    private static Vector2d project(Vector p, Vector right, Vector forward) {
        return new Vector2d(p.dot(right), p.dot(forward));
    }

    private static Vector unProject(Vector2d point, Vector right, Vector forward, Vector up, double polygonAlongUp) {
        return right.clone().multiply(point.x)
            .add(forward.clone().multiply(point.y))
            .add(up.clone().multiply(polygonAlongUp));
    }

    public static void setupSpiderBody(ECS app) {
        app.onTick(ecs -> {
            for (Triple<ECSEntity, SpiderBody, SpiderOptions> it : app.query(ECSEntity.class, SpiderBody.class, SpiderOptions.class)) {
                it.second().update(app, it.first(), it.third());
            }
        });
    }

}
