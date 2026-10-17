package net.tfminecraft.spiders.spider.components.body;

import java.util.List;

public final class GallopGaitType {
    private GallopGaitType() {
    }

    public static List<Leg> getLegsInUpdateOrder(SpiderBody spider) {
        return WalkGaitType.getLegsInUpdateOrder(spider);
    }

    public static boolean canMoveLeg(Leg leg) {
        SpiderBody spider = leg.getSpider();
        int index = spider.getLegs().indexOf(leg);

        if (!spider.isWalking()) return WalkGaitType.canMoveLeg(leg);

        // always move if the target is not on ground
        if (!leg.getTarget().isGrounded()) return true;

        // only move when at least one leg is on the ground
        boolean onGround = false;
        for (Leg it : spider.getLegs()) {
            if (it.isGrounded()) {
                onGround = true;
                break;
            }
        }
        onGround = onGround || spider.getOnGround();
        if (!onGround) return false;

        // an unpaired leg (odd leg count) walks instead of galloping
        Leg pair = getOrNull(spider.getLegs(), LegLookUp.horizontal(index));
        if (pair == null) return WalkGaitType.canMoveLeg(leg);

        leg.setPrimary(LegLookUp.isDiagonal1(index) || pair.isDisabled() || !pair.getTarget().isGrounded());

        if (leg.isPrimary()) {
            // check cooldown
            Leg front = getOrNull(spider.getLegs(), LegLookUp.diagonalFront(index));
            Leg back = getOrNull(spider.getLegs(), LegLookUp.diagonalBack(index));
            // the target is grounded here (an ungrounded one returned above)
            if (front != null && (leg.getTimeSinceBeginMove() < leg.getOptions().getGait().getCrossPairCooldown())) return false;

            return leg.isOutsideTriggerZone() || !leg.getTouchingGround();
        } else {
            // a secondary leg's pair is enabled and its target grounded (otherwise this leg would be primary)
            boolean hasCooldown = pair.getTimeSinceBeginMove() < leg.getOptions().getGait().getSamePairCooldown();
            return pair.isMoving() && !hasCooldown;
        }
    }

    private static Leg getOrNull(List<Leg> legs, int index) {
        return index >= 0 && index < legs.size() ? legs.get(index) : null;
    }
}
