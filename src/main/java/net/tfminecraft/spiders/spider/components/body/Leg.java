package net.tfminecraft.spiders.spider.components.body;

import net.tfminecraft.spiders.spider.configuration.Gait;
import net.tfminecraft.spiders.spider.configuration.LegPlan;
import net.tfminecraft.spiders.spider.configuration.Posture;
import net.tfminecraft.spiders.spider.configuration.SegmentPlan;
import net.tfminecraft.spiders.spider.configuration.SpiderOptions;
import net.tfminecraft.spiders.utilities.Capsule;
import net.tfminecraft.spiders.utilities.CollisionResult;
import net.tfminecraft.spiders.utilities.Geometry;
import net.tfminecraft.spiders.utilities.LineSegment;
import net.tfminecraft.spiders.utilities.ecs.ECS;
import net.tfminecraft.spiders.utilities.ecs.ECSEntity;
import net.tfminecraft.spiders.utilities.maths.Maths;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;

import java.util.Arrays;
import java.util.List;

public class Leg {
    private static final double OBSTRUCTION_FRACTION = .9;
    private static final double SAME_SURFACE_MIN_DOT = .7;
    private static final double UPRIGHT_MIN_UP = .7;
    private static final double FLOOR_PROBE = .01;
    private static final double CLIMB_REACH_FRACTION = .95;
    private static final int OBSTRUCTED_TARGET_ID = -2;
    private static final int WRAPPED_TARGET_ID = -3;
    /** Ticks between ground scans for a leg that isn't stepping (mid-leap or disabled). */
    private static final int IDLE_SCAN_TICKS = 3;
    /** Ticks a still leg reuses its last ground scan and collision check before casting them again, so block changes are still noticed. */
    public static final int SCAN_REFRESH_TICKS = 5;
    private static final float ORIENTATION_EPSILON = 1e-6f;

    private final ECS ecs;
    private final ECSEntity entity;
    private final SpiderBody spider;
    private final LegPlan legPlan;
    private final SpiderOptions options;

    // memo
    private Capsule triggerZone;
    private Capsule comfortZone;

    private Vector restPosition;
    private Vector lookAheadPosition;
    private LineSegment scanLine;
    /** Surface normal the leg steps against: world up, or the body's up while climbing. */
    private Vector surfaceUp;

    // state
    private LegTarget groundTarget;
    private LegTarget target;
    private Vector endEffector;
    private Vector previousEndEffector;

    private final SpiderLegIK ik;

    // ray cache: a leg whose situation hasn't changed reuses its last answers for a few ticks
    private int ticks;
    private final ScanCache scanCache = new ScanCache();
    private final CollisionCache collisionCache = new CollisionCache();

    private boolean touchingGround;
    private boolean isMoving;
    private int timeSinceBeginMove;
    private int timeSinceStopMove;

    private Vector stepStart;
    private double stepProgress; // separate from timeSinceBeginMove since progress can soft-reset on re-target

    private boolean isDisabled;
    private boolean isPrimary;
    private boolean canMove;
    /** Idle ticks left before the next ground scan; starts staggered by leg so idle legs don't all scan together. */
    private int idleScanCountdown;

    public Leg(ECS ecs, ECSEntity entity, SpiderBody spider, LegPlan legPlan, SpiderOptions options) {
        this.ecs = ecs;
        this.entity = entity;
        this.spider = spider;
        this.legPlan = legPlan;
        this.options = options;

        updateMemo();

        // state
        this.groundTarget = locateGroundTarget();
        LegTarget ground = this.groundTarget;
        this.target = ground != null ? ground : strandedTarget();
        this.endEffector = target.getPosition().clone();
        this.previousEndEffector = endEffector.clone();

        this.ik = SpiderLegIK.createSpiderLegIK(legPlan);

        this.touchingGround = target.isGrounded();
        this.isMoving = false;
        this.timeSinceBeginMove = 0;
        this.timeSinceStopMove = 0;

        this.stepStart = new Vector(0, 0, 0);
        this.stepProgress = 0.0;

        this.isDisabled = false;
        this.isPrimary = false;
        this.canMove = false;
        this.idleScanCountdown = Math.max(0, options.getBodyPlan().getLegs().indexOf(legPlan)) % IDLE_SCAN_TICKS;
    }

    public ECS getEcs() {
        return ecs;
    }

    public ECSEntity getEntity() {
        return entity;
    }

    public SpiderBody getSpider() {
        return spider;
    }

    public LegPlan getLegPlan() {
        return legPlan;
    }

    public SpiderOptions getOptions() {
        return options;
    }

    public Capsule getTriggerZone() {
        return triggerZone;
    }

    public Capsule getComfortZone() {
        return comfortZone;
    }

    public Vector getRestPosition() {
        return restPosition;
    }

    public Vector getLookAheadPosition() {
        return lookAheadPosition;
    }

    public LineSegment getScanLine() {
        return scanLine;
    }

    /** Surface normal the leg steps against: world up, or the body's up while climbing. */
    public Vector getSurfaceUp() {
        return surfaceUp;
    }

    public LegTarget getGroundTarget() {
        return groundTarget;
    }

    public LegTarget getTarget() {
        return target;
    }

    public void setTarget(LegTarget target) {
        this.target = target;
    }

    public Vector getEndEffector() {
        return endEffector;
    }

    public void setEndEffector(Vector endEffector) {
        this.endEffector = endEffector;
    }

    public Vector getPreviousEndEffector() {
        return previousEndEffector;
    }

    public void setPreviousEndEffector(Vector previousEndEffector) {
        this.previousEndEffector = previousEndEffector;
    }

    public SpiderLegIK getIk() {
        return ik;
    }

    public boolean getTouchingGround() {
        return touchingGround;
    }

    public boolean isMoving() {
        return isMoving;
    }

    public int getTimeSinceBeginMove() {
        return timeSinceBeginMove;
    }

    public int getTimeSinceStopMove() {
        return timeSinceStopMove;
    }

    public boolean isDisabled() {
        return isDisabled;
    }

    public void setDisabled(boolean disabled) {
        this.isDisabled = disabled;
    }

    public boolean isPrimary() {
        return isPrimary;
    }

    public void setPrimary(boolean primary) {
        this.isPrimary = primary;
    }

    public boolean getCanMove() {
        return canMove;
    }

    public void setCanMove(boolean canMove) {
        this.canMove = canMove;
    }

    // utils
    public boolean isOutsideTriggerZone() {
        return !triggerZone.contains(endEffector);
    }

    public boolean isUncomfortable() {
        return !comfortZone.contains(endEffector) && !isSettledOnClimbingFoothold();
    }

    /** A climbing foot already on its best (reachable but awkward) foothold can't do better, so it mustn't stall the body. */
    private boolean isSettledOnClimbingFoothold() {
        return options.getGait().getGrip() && target.isGrounded() && !isMoving && endEffector.distanceSquared(target.getPosition()) < 0.01;
    }

    public boolean isGrounded() {
        return touchingGround && !isMoving && !isDisabled;
    }

    public void updateMemo() {
        Posture posture = spider.posture(options.getGait());
        Quaternionf scanOrientation = options.getGait().getScanOrientation().get(spider);


        // rest position
        restPosition = legPlan.getRestPosition().clone();
        restPosition.add(Maths.upVector().clone().multiply(-posture.getBodyHeight()));
        Maths.rotate(restPosition, scanOrientation).add(spider.getPosition());
        surfaceUp = Maths.rotate(Maths.upVector(), scanOrientation);

        // lookahead
        lookAheadPosition = lookAheadPosition(restPosition, posture.getTriggerZoneRadius());

        // scan (from lookahead position)
        Vector upVector = Maths.rotate(Maths.upVector(), scanOrientation);
        Vector scanStartAxis = upVector.clone().multiply(posture.getBodyHeight() * 1.6);
        Vector scanAxis = upVector.clone().multiply(-posture.getBodyHeight() * 2.5);
        scanLine = LineSegment.fromOffset(lookAheadPosition.clone().add(scanStartAxis), scanAxis);

        // trigger/comfort zone capsules (from rest position; same axis as scan)
        Vector zoneStart = restPosition.clone().add(scanStartAxis);
        Vector zoneEnd = zoneStart.clone().add(scanAxis);
        triggerZone = new Capsule(zoneStart, zoneEnd, posture.getTriggerZoneRadius());
        comfortZone = new Capsule(zoneStart, zoneEnd, options.getGait().getComfortZoneRadius());
    }

    public void update() {
        ticks += 1;
        updateMovement();
        ik.solve(spider.getPosition(), spider.getOrientation(), endEffector);
    }

    private void updateMovement() {
        previousEndEffector = endEffector.clone();

        timeSinceBeginMove += 1;
        timeSinceStopMove += 1;

        // update target
        Vector oldTargetPosition = target.getPosition().clone();
        if (shouldScanGround()) groundTarget = locateGroundTarget();
        LegTarget ground = groundTarget;

        if (isDisabled) {
            target = disabledTarget(ground != null ? ground.getPosition() : null);
        } else if (spider.getLeapTicks() > 0) {
            // mid-leap: legs reach to their pose under the flying body
            target = strandedTarget();
        } else {
            if (ground != null) target = ground;

            if (!target.isGrounded() || !canReach(target.getPosition()) || !canStandOn(target.getPosition())) {
                target = strandedTarget();
            }
        }

        // reset step if target has changed
        boolean targetChanged = oldTargetPosition.distanceSquared(target.getPosition()) > 1e-6;
        if (targetChanged) {
            softResetStep();
        }

        // inherit parent motion while free
        if (!isGrounded()) {
            applyBodyMotion(endEffector);
            if (isMoving) applyBodyMotion(stepStart);
        }

        boolean didStep = false;
        if (isMoving) {
            didStep = updateMove();
        } else {
            canMove = options.getGait().getType().canMoveLeg(this);
            if (canMove) beginMove();
        }


        // resolve ground collision
        Quaternionf scanOrientation = options.getGait().getScanOrientation().get(spider);
        Vector down = Maths.rotate(Maths.downVector(), scanOrientation);
        CollisionResult collision = resolveGroundCollision(down);
        if (collision != null) {
            // ignore collision if it would push the end effector further from the target
            // e.g. when grazing an obstacle
            boolean isFurtherFromTarget = collision.getPosition().distance(target.getPosition()) > endEffector.distance(target.getPosition());
            if (!isFurtherFromTarget) {
                didStep = true;
                touchingGround = true;
                endEffector.copy(collision.getPosition());
            }
        }

        if (didStep) ecs.emit(new LegStepEvent(entity, spider, this));
    }

    /**
     * A stepping leg scans every tick. Mid-leap or disabled, the ground is only read for body tilt
     * and to keep a raised foot clear, so it is refreshed every {@link #IDLE_SCAN_TICKS} ticks.
     */
    private boolean shouldScanGround() {
        if (!isScanThrottled()) return true;
        if (idleScanCountdown > 0) {
            idleScanCountdown--;
            return false;
        }
        idleScanCountdown = IDLE_SCAN_TICKS - 1;
        return true;
    }

    /** Mid-leap or disabled: the leg isn't stepping, so it only scans every {@link #IDLE_SCAN_TICKS} ticks. */
    private boolean isScanThrottled() {
        return isDisabled || spider.getLeapTicks() > 0;
    }

    /** {@link Geometry#resolveCollision} for the foot; a still foot whose ray hasn't moved reuses the last answer. */
    private CollisionResult resolveGroundCollision(Vector down) {
        if (!isMoving && collisionCache.matches(endEffector, down)) return collisionCache.result;
        CollisionResult result = Geometry.resolveCollision(spider.getWorld(), endEffector, down);
        collisionCache.remember(endEffector, down, result);
        return result;
    }

    private void applyBodyMotion(Vector point) {
        point.add(spider.getVelocity());
        rotateWithBody(point);
    }

    /** Follow the body's turn about the surface normal (yaw on flat ground). */
    private void rotateWithBody(Vector point) {
        double angle = (double) spider.getRotationalVelocity().dot(surfaceUp.toVector3f());
        if (angle == 0.0) return;
        point.subtract(spider.getPosition()).rotateAroundAxis(surfaceUp, angle).add(spider.getPosition());
    }

    /** Without grip a foot can only stand on an upward face; one left on a wall or ceiling lets go. */
    private boolean canStandOn(Vector point) {
        if (options.getGait().getGrip()) return true;
        // upright spiders only ever find upward faces, so skip the probe
        if (Maths.rotate(Maths.upVector(), spider.getOrientation()).getY() > UPRIGHT_MIN_UP) return true;
        // the probe must start in air just above the foot and meet a face below it
        Vector above = point.clone().add(new Vector(0.0, FLOOR_PROBE, 0.0));
        RayTraceResult hit = Geometry.raycastGround(spider.getWorld(), above, Maths.downVector(), FLOOR_PROBE * 2);
        if (hit == null) return false;
        return hit.getHitPosition().distanceSquared(above) > (FLOOR_PROBE / 10) * (FLOOR_PROBE / 10);
    }

    /** Footholds must lie in the comfort zone; climbing legs may also reach any face within leg length. */
    private boolean canReach(Vector point) {
        if (comfortZone.contains(point)) return true;
        if (!options.getGait().getGrip()) return false;
        Vector hip = Maths.rotate(legPlan.getAttachmentPosition().clone(), spider.getOrientation()).add(spider.getPosition());
        double sum = 0.0;
        for (SegmentPlan it : legPlan.getSegments()) sum += it.getLength();
        double reach = sum * CLIMB_REACH_FRACTION;
        return hip.distanceSquared(point) <= reach * reach;
    }

    private void beginMove() {
        isMoving = true;
        timeSinceBeginMove = 0;
        stepStart = endEffector.clone();
        stepProgress = 0.0;
        touchingGround = false;
    }

    private boolean completeMove() {
        isMoving = false;
        timeSinceStopMove = 0;
        endEffector.copy(target.getPosition());
        touchingGround = touchingGround();
        return touchingGround;
    }

    /** Parabolic height envelope: 0 at ends, 1 at mid-step. */
    private double stepLiftFactor(double t) {
        return 4.0 * t * (1.0 - t);
    }

    private Vector sampleStepPosition(double t) {
        Vector position = new Vector(
            Maths.lerp(stepStart.getX(), target.getPosition().getX(), t),
            Maths.lerp(stepStart.getY(), target.getPosition().getY(), t),
            Maths.lerp(stepStart.getZ(), target.getPosition().getZ(), t)
        );
        position.add(surfaceUp.clone().multiply(options.getGait().getLegLiftHeight() * stepLiftFactor(t)));
        return position;
    }

    private boolean updateMove() {
        Gait gait = options.getGait();

        double distance = Math.max(planarDistance(stepStart, target.getPosition()), 1e-4);
        stepProgress = Maths.moveTowards(stepProgress, 1.0, gait.getLegMoveSpeed() / distance);

        endEffector.copy(sampleStepPosition(stepProgress));

        if (stepProgress >= 1.0) {
            return completeMove();
        }

        return false;
    }

    private void softResetStep() {
        stepStart = endEffector.clone();
        stepProgress = 0.0;
    }

    private boolean touchingGround() {
        Quaternionf scanOrientation = options.getGait().getScanOrientation().get(spider);
        return Geometry.raycastGround(spider.getWorld(), endEffector, Maths.rotate(Maths.downVector(), scanOrientation), 0.001) != null;
    }

    private Vector lookAheadPosition(Vector restPosition, double triggerZoneRadius) {
        if (!spider.isWalking()) return restPosition;

        Vector direction = spider.getVelocity().isZero() ? spider.forwardDirection() : spider.getVelocity().clone().normalize();

        Vector lookAhead = direction.multiply(triggerZoneRadius * options.getGait().getLegLookAheadFraction()).add(restPosition);
        rotateWithBody(lookAhead);
        return lookAhead;
    }


    /**
     * The foothold the ground scan finds; a leg whose scan inputs haven't changed reuses its last result for up to {@link #SCAN_REFRESH_TICKS}.
     * A throttled leg already skips most scans, so the ones it runs are always fresh and still notice block changes in time.
     */
    public LegTarget locateGroundTarget() {
        if (isScanThrottled() || !scanCache.matches()) scanCache.remember(scanGroundTarget());
        return scanCache.copy();
    }

    private LegTarget scanGroundTarget() {
        if (!options.getGait().getGrip()) return locateScanTarget();

        // Climbing: a face between the body and the foothold (wall or ceiling ahead) comes first,
        // then the normal scan, then the face around a convex edge.
        LegTarget obstructed = locateObstructedTarget();
        if (obstructed != null) return obstructed;
        LegTarget scanned = locateScanTarget();
        if (scanned != null) return scanned;
        return locateWrappedTarget();
    }

    private LegTarget locateScanTarget() {
        Vector up = surfaceUp;
        Vector scanStartPosition = scanLine.getPoint1();
        Vector scanVector = scanLine.vector();
        double scanLength = scanVector.length();

        // alternative candidates snap to block edges along the two axes lying in the surface
        int[] axes = surfaceAxes(up);
        int axisA = axes[0];
        int axisB = axes[1];

        ScanRayCaster rayCaster = new ScanRayCaster(spider.getWorld(), scanStartPosition, scanVector, scanLength, axisA, axisB);

        double a = axis(scanStartPosition, axisA);
        double b = axis(scanStartPosition, axisB);

        LegTarget mainCandidate = rayCaster.rayCast(a, b);

        if (!options.getGait().getLegScanAlternativeGround()) return mainCandidate;

        if (mainCandidate != null) {
            double height = mainCandidate.getPosition().clone().subtract(lookAheadPosition).dot(up);
            if (height >= -.24 && height <= 1.5) {
                return mainCandidate;
            }
        }

        double margin = 2 / 16.0;
        double na = Math.floor(a) - margin;
        double nb = Math.floor(b) - margin;
        double pb = Math.ceil(b) + margin;
        double pa = Math.ceil(a) + margin;

        List<LegTarget> candidates = Arrays.asList(
            rayCaster.rayCast(na, nb), rayCaster.rayCast(na, b), rayCaster.rayCast(na, pb),
            rayCaster.rayCast(a, nb),  mainCandidate,            rayCaster.rayCast(a, pb),
            rayCaster.rayCast(pa, nb), rayCaster.rayCast(pa, b), rayCaster.rayCast(pa, pb)
        );

        Vector preferredPosition = lookAheadPosition.clone();

        Block frontBlock = lookAheadPosition.clone().add(spider.forwardDirection()).toLocation(spider.getWorld()).getBlock();
        if (!frontBlock.isPassable()) preferredPosition.add(up.clone().multiply(options.getGait().getLegScanHeightBias()));

        LegTarget best = null;
        double bestDistance = 0.0;
        for (LegTarget it : candidates) {
            if (it == null) continue;
            double distance = it.getPosition().distanceSquared(preferredPosition);
            if (best == null || Double.compare(bestDistance, distance) > 0) {
                best = it;
                bestDistance = distance;
            }
        }

        if (best != null && !canReach(best.getPosition())) {
            return null;
        }

        return best;
    }

    private final class ScanRayCaster {
        private final World world;
        private final Vector scanStartPosition;
        private final Vector scanVector;
        private final double scanLength;
        private final int axisA;
        private final int axisB;
        private int id = 0;

        ScanRayCaster(World world, Vector scanStartPosition, Vector scanVector, double scanLength, int axisA, int axisB) {
            this.world = world;
            this.scanStartPosition = scanStartPosition;
            this.scanVector = scanVector;
            this.scanLength = scanLength;
            this.axisA = axisA;
            this.axisB = axisB;
        }

        LegTarget rayCast(double a, double b) {
            id += 1;

            Vector start = withAxis(withAxis(scanStartPosition.clone(), axisA, a), axisB, b);
            RayTraceResult hit = Geometry.raycastGround(world, start, scanVector, scanLength);
            if (hit == null) return null;

            // a climbing scan that starts inside a wall is not a foothold
            if (options.getGait().getGrip() && hit.getHitPosition().distanceSquared(start) < 1e-6) return null;

            return new LegTarget(hit.getHitPosition(), true, id);
        }
    }

    /**
     * The last ground scan and everything it read; the forward direction it reads follows from the body's orientation.
     * <p>
     * The scan line is the look-ahead plus {@code 1.6 h} surface ups to {@code -2.5 h} of them (body height {@code h}),
     * so its two ends fix the look-ahead and the surface up as well, whatever moved them (walking, the velocity's
     * direction, the look-ahead fraction, the trigger radius, the scan orientation). The comfort zone runs from
     * its start along that same axis. So the look-ahead, the surface up and the comfort zone's end aren't compared on their own.
     */
    private final class ScanCache {
        private boolean valid = false;
        private int tick;
        private LegTarget result;

        private final Vector spiderPosition = new Vector();
        private final Quaternionf spiderOrientation = new Quaternionf();
        private final Vector scanStart = new Vector();
        private final Vector scanEnd = new Vector();
        private final Vector comfortStart = new Vector();
        private double comfortRadius;
        private boolean grip;
        private boolean alternativeGround;
        private double heightBias;

        boolean matches() {
            if (!valid || ticks - tick >= SCAN_REFRESH_TICKS) return false;
            Gait gait = options.getGait();
            return grip == gait.getGrip() && alternativeGround == gait.getLegScanAlternativeGround() && heightBias == gait.getLegScanHeightBias() &&
                spiderPosition.equals(spider.getPosition()) && spiderOrientation.equals(spider.getOrientation(), ORIENTATION_EPSILON) &&
                scanStart.equals(scanLine.getPoint1()) && scanEnd.equals(scanLine.getPoint2()) &&
                comfortStart.equals(comfortZone.getPoint1()) && comfortRadius == comfortZone.getRadius();
        }

        void remember(LegTarget result) {
            Gait gait = options.getGait();
            this.valid = true;
            this.tick = ticks;
            this.result = result;
            spiderPosition.copy(spider.getPosition());
            spiderOrientation.set(spider.getOrientation());
            scanStart.copy(scanLine.getPoint1());
            scanEnd.copy(scanLine.getPoint2());
            comfortStart.copy(comfortZone.getPoint1());
            comfortRadius = comfortZone.getRadius();
            grip = gait.getGrip();
            alternativeGround = gait.getLegScanAlternativeGround();
            heightBias = gait.getLegScanHeightBias();
        }

        /** The cached result with its own position, since target positions are mutable. */
        LegTarget copy() {
            return result == null ? null : new LegTarget(result.getPosition().clone(), result.isGrounded(), result.getId());
        }
    }

    /** The last collision ray: a pure function of the foot and its direction, so exact inputs give the same answer until a block changes. */
    private final class CollisionCache {
        private boolean valid = false;
        private int tick;
        private CollisionResult result;
        private final Vector position = new Vector();
        private final Vector direction = new Vector();

        boolean matches(Vector position, Vector direction) {
            return valid && ticks - tick < SCAN_REFRESH_TICKS && same(this.position, position) && same(this.direction, direction);
        }

        void remember(Vector position, Vector direction, CollisionResult result) {
            this.valid = true;
            this.tick = ticks;
            this.result = result;
            this.position.copy(position);
            this.direction.copy(direction);
        }

        private static boolean same(Vector a, Vector b) {
            return a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
        }
    }

    /** A wall or ceiling face crossing the line from the body to the desired foothold. */
    private LegTarget locateObstructedTarget() {
        Vector toFoothold = lookAheadPosition.clone().subtract(spider.getPosition());
        double distance = toFoothold.length();
        if (distance < 1e-6) return null;

        RayTraceResult hit = Geometry.raycastGround(spider.getWorld(), spider.getPosition(), toFoothold, distance * OBSTRUCTION_FRACTION);
        if (hit == null) return null;
        if (hit.getHitPosition().distanceSquared(spider.getPosition()) < 1e-6) return null;
        // the surface already underfoot is left to the normal scan
        BlockFace face = hit.getHitBlockFace();
        if (face == null) return null;
        Vector normal = face.getDirection();
        if (normal.dot(surfaceUp) > SAME_SURFACE_MIN_DOT) return null;
        if (!canReach(hit.getHitPosition())) return null;

        return new LegTarget(hit.getHitPosition(), true, OBSTRUCTED_TARGET_ID);
    }

    /** Past a convex edge the scan misses, so look back under the body for the face around the corner. */
    private LegTarget locateWrappedTarget() {
        // from the end of the scan, back towards the body parallel to the surface it stands on
        Vector start = scanLine.getPoint2();
        Vector toBody = planarOffset(start, spider.getPosition());
        double distance = toBody.length();
        if (distance < 1e-6) return null;

        RayTraceResult hit = Geometry.raycastGround(spider.getWorld(), start, toBody, distance);
        if (hit == null) return null;
        if (hit.getHitPosition().distanceSquared(start) < 1e-6) return null;
        if (!canReach(hit.getHitPosition())) return null;

        return new LegTarget(hit.getHitPosition(), true, WRAPPED_TARGET_ID);
    }

    /** Offset from {@code from} to {@code to} along the surface, with the component along its normal removed. */
    private Vector planarOffset(Vector from, Vector to) {
        Vector offset = to.clone().subtract(from);
        return offset.subtract(surfaceUp.clone().multiply(offset.dot(surfaceUp)));
    }

    private double planarDistance(Vector from, Vector to) {
        return planarOffset(from, to).length();
    }

    private LegTarget strandedTarget() {
        return new LegTarget(lookAheadPosition.clone(), false, -1);
    }

    private LegTarget disabledTarget(Vector groundPosition) {
        Posture posture = spider.posture(options.getGait());
        Vector upVector = Maths.rotate(Maths.upVector(), spider.getOrientation());

        LegTarget target = strandedTarget();
        target.getPosition().add(upVector.clone().multiply(posture.getBodyHeight() * .5));

        // keep the raised foot clear of the surface it stood on
        if (groundPosition != null) {
            double minHeight = groundPosition.dot(surfaceUp) + posture.getBodyHeight() * .1;
            double height = target.getPosition().dot(surfaceUp);
            if (height < minHeight) target.getPosition().add(surfaceUp.clone().multiply(minHeight - height));
        }

        return target;
    }

    /** The two block axes closest to lying in a surface with this normal (x and z on flat ground). */
    private static int[] surfaceAxes(Vector up) {
        double x = Math.abs(up.getX());
        double y = Math.abs(up.getY());
        double z = Math.abs(up.getZ());
        if (y >= x && y >= z) return new int[]{0, 2};
        if (x >= z) return new int[]{1, 2};
        return new int[]{0, 1};
    }

    private static double axis(Vector vector, int axis) {
        return switch (axis) {
            case 0 -> vector.getX();
            case 1 -> vector.getY();
            default -> vector.getZ();
        };
    }

    private static Vector withAxis(Vector vector, int axis, double value) {
        return switch (axis) {
            case 0 -> vector.setX(value);
            case 1 -> vector.setY(value);
            default -> vector.setZ(value);
        };
    }
}
