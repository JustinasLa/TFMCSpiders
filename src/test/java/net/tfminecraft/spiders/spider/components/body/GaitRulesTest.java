package net.tfminecraft.spiders.spider.components.body;

import net.tfminecraft.spiders.spider.configuration.Gait;
import net.tfminecraft.spiders.spider.configuration.SpiderOptions;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The walk and gallop stepping rules, on legs whose state is set directly. */
class GaitRulesTest {
    /** A leg with a grounded target that wants to step (outside its trigger zone), resting on the ground. */
    private static final class LegState {
        boolean grounded = true;
        boolean targetGrounded = true;
        boolean disabled = false;
        boolean moving = false;
        boolean touchingGround = true;
        boolean outsideTriggerZone = true;
        int sinceBeginMove = 100;
        int sinceStopMove = 100;
        Vector endEffector = new Vector(0.0, 0.0, 0.0);
        Vector target = new Vector(1.0, 0.0, 0.0);
        boolean primary;
    }

    private final Gait gait = Gait.defaultGallop();
    private final SpiderBody spider = mock(SpiderBody.class);
    private final List<Leg> legs = new ArrayList<>();
    private final List<LegState> states = new ArrayList<>();

    /** {@code count} legs on a spider that is on the ground; walking makes the gallop rule apply. */
    private void legs(int count, boolean walking) {
        SpiderOptions options = mock(SpiderOptions.class);
        when(options.getGait()).thenReturn(gait);
        when(spider.getLegs()).thenReturn(legs);
        when(spider.isWalking()).thenReturn(walking);
        when(spider.getOnGround()).thenReturn(true);
        for (int i = 0; i < count; i++) {
            LegState state = new LegState();
            Leg leg = mock(Leg.class);
            when(leg.getSpider()).thenReturn(spider);
            when(leg.getOptions()).thenReturn(options);
            when(leg.isGrounded()).thenAnswer(call -> state.grounded);
            when(leg.getTarget()).thenAnswer(call -> new LegTarget(state.target, state.targetGrounded, 0));
            when(leg.isDisabled()).thenAnswer(call -> state.disabled);
            when(leg.isMoving()).thenAnswer(call -> state.moving);
            when(leg.getTouchingGround()).thenAnswer(call -> state.touchingGround);
            when(leg.isOutsideTriggerZone()).thenAnswer(call -> state.outsideTriggerZone);
            when(leg.getTimeSinceBeginMove()).thenAnswer(call -> state.sinceBeginMove);
            when(leg.getTimeSinceStopMove()).thenAnswer(call -> state.sinceStopMove);
            when(leg.getEndEffector()).thenAnswer(call -> state.endEffector);
            doAnswer(call -> {
                state.primary = call.getArgument(0);
                return null;
            }).when(leg).setPrimary(anyBoolean());
            when(leg.isPrimary()).thenAnswer(call -> state.primary);
            legs.add(leg);
            states.add(state);
        }
    }

    @Test
    @DisplayName("walk: a leg steps with no foot down only while the body rests on the ground")
    void walkLegStepsWithNoFootDownOnlyWhileTheBodyRestsOnTheGround() {
        legs(4, false);
        // leg 0's neighbours (1 and 2) are disabled, so they don't hold it back; nobody stands
        for (LegState state : states) state.grounded = false;
        states.get(1).disabled = true;
        states.get(2).disabled = true;

        assertTrue(WalkGaitType.canMoveLeg(legs.get(0)), "Body on the ground");
        verify(legs.get(0)).setPrimary(true);
        when(spider.getOnGround()).thenReturn(false);
        assertFalse(WalkGaitType.canMoveLeg(legs.get(0)), "Body in the air with no foot down");

        // one grounded foot anywhere is enough
        states.get(3).grounded = true;
        assertTrue(WalkGaitType.canMoveLeg(legs.get(0)));
    }

    @Test
    @DisplayName("walk: a leg waits for its neighbours and its diagonal partners")
    void walkLegWaitsForItsNeighboursAndDiagonalPartners() {
        legs(4, false);
        Leg leg = legs.get(0);
        assertTrue(WalkGaitType.canMoveLeg(leg));

        // a lifted neighbour heading for the ground holds it back
        states.get(1).grounded = false;
        assertFalse(WalkGaitType.canMoveLeg(leg));
        states.get(1).targetGrounded = false;
        assertTrue(WalkGaitType.canMoveLeg(leg), "A neighbour that has nowhere to stand doesn't count");
        states.get(1).grounded = true;
        states.get(1).targetGrounded = true;

        // a neighbour that has only just landed
        states.get(2).sinceStopMove = 0;
        assertFalse(WalkGaitType.canMoveLeg(leg));
        states.get(2).sinceStopMove = 100;

        // a diagonal partner (leg 3) that only just lifted
        states.get(3).sinceBeginMove = 0;
        assertFalse(WalkGaitType.canMoveLeg(leg));
        states.get(3).targetGrounded = false;
        assertTrue(WalkGaitType.canMoveLeg(leg), "A partner without a foothold doesn't hold it back");
        states.get(3).targetGrounded = true;
        states.get(3).sinceBeginMove = 100;

        // nothing to do inside the trigger zone with the foot down, or already at the target
        states.get(0).outsideTriggerZone = false;
        assertFalse(WalkGaitType.canMoveLeg(leg));
        states.get(0).touchingGround = false;
        assertTrue(WalkGaitType.canMoveLeg(leg), "A foot off the ground steps");
        states.get(0).endEffector = states.get(0).target.clone();
        assertFalse(WalkGaitType.canMoveLeg(leg), "Already at the target");

        // a stranded target always steps
        states.get(0).targetGrounded = false;
        states.get(1).grounded = false;
        assertTrue(WalkGaitType.canMoveLeg(leg));
    }

    @Test
    @DisplayName("gallop: not walking it uses the walk rule; a stranded target always steps")
    void gallopFallsBackToWalkWhenStill() {
        legs(4, false);
        states.get(1).grounded = false; // holds leg 0 back under the walk rule
        assertFalse(GallopGaitType.canMoveLeg(legs.get(0)));
        when(spider.isWalking()).thenReturn(true);
        states.get(0).targetGrounded = false;
        assertTrue(GallopGaitType.canMoveLeg(legs.get(0)));
    }

    @Test
    @DisplayName("gallop: legs only step while a foot or the body is on the ground")
    void gallopNeedsAFootOrTheBodyOnTheGround() {
        legs(4, true);
        for (LegState state : states) state.grounded = false;
        assertTrue(GallopGaitType.canMoveLeg(legs.get(0)), "Body on the ground");
        when(spider.getOnGround()).thenReturn(false);
        assertFalse(GallopGaitType.canMoveLeg(legs.get(0)), "Airborne");
        states.get(3).grounded = true;
        assertTrue(GallopGaitType.canMoveLeg(legs.get(0)), "One foot down");
    }

    @Test
    @DisplayName("gallop: a primary leg leads, waiting out the cross-pair cooldown")
    void gallopPrimaryLegLeads() {
        legs(4, true);
        // leg 0 is on the first diagonal: primary, and has no diagonal front leg so no cooldown
        states.get(0).sinceBeginMove = 0;
        assertTrue(GallopGaitType.canMoveLeg(legs.get(0)));
        assertTrue(states.get(0).primary);
        states.get(0).outsideTriggerZone = false;
        assertFalse(GallopGaitType.canMoveLeg(legs.get(0)), "Comfortable and standing");
        states.get(0).touchingGround = false;
        assertTrue(GallopGaitType.canMoveLeg(legs.get(0)), "Foot off the ground");

        // leg 3 (second pair, right) is on the first diagonal and has a diagonal front leg (0)
        states.get(3).sinceBeginMove = 0;
        assertFalse(GallopGaitType.canMoveLeg(legs.get(3)), "Cross-pair cooldown");
        states.get(3).sinceBeginMove = 100;
        assertTrue(GallopGaitType.canMoveLeg(legs.get(3)));
        assertTrue(states.get(3).primary);
    }

    @Test
    @DisplayName("gallop: a secondary leg follows its pair, which leads when it can't")
    void gallopSecondaryLegFollowsItsPair() {
        legs(4, true);
        Leg leg = legs.get(1); // the right front leg, second diagonal, paired with leg 0
        LegState pair = states.get(0);

        assertFalse(GallopGaitType.canMoveLeg(leg), "Pair standing still");
        assertFalse(states.get(1).primary);
        pair.moving = true;
        assertTrue(GallopGaitType.canMoveLeg(leg), "Pair stepping");
        pair.sinceBeginMove = 0;
        assertFalse(GallopGaitType.canMoveLeg(leg), "Same-pair cooldown");
        pair.sinceBeginMove = 100;

        // with the pair disabled or stranded, this leg leads instead
        pair.disabled = true;
        assertTrue(GallopGaitType.canMoveLeg(leg));
        assertTrue(states.get(1).primary);
        pair.disabled = false;
        pair.targetGrounded = false;
        states.get(1).outsideTriggerZone = false;
        assertFalse(GallopGaitType.canMoveLeg(leg), "Leads, but has no reason to step");
        assertTrue(states.get(1).primary);
    }

    @Test
    @DisplayName("gallop: an unpaired leg (odd leg count) walks")
    void gallopUnpairedLegWalks() {
        legs(5, true);
        Leg last = legs.get(4);
        assertTrue(GallopGaitType.canMoveLeg(last));
        // under the walk rule a lifted neighbour (leg 2) holds it back
        states.get(2).grounded = false;
        assertFalse(GallopGaitType.canMoveLeg(last));
    }

    @Test
    @DisplayName("both gaits update the first diagonal before the second")
    void bothGaitsUpdateTheFirstDiagonalFirst() {
        legs(6, true);
        List<Leg> expected = List.of(legs.get(0), legs.get(3), legs.get(4), legs.get(1), legs.get(2), legs.get(5));
        assertEquals(expected, GaitType.WALK.getLegsInUpdateOrder(spider));
        assertEquals(expected, GaitType.GALLOP.getLegsInUpdateOrder(spider));
        assertSame(legs.get(2), GaitType.unIndexLeg(spider, List.of(-1, 2, 6)).get(0));
        assertEquals(1, GaitType.unIndexLeg(spider, List.of(-1, 2, 6)).size());
    }

    @Test
    @DisplayName("each gait type dispatches to its own rule")
    void eachGaitTypeDispatchesToItsOwnRule() {
        legs(4, true);
        // leg 1 under the walk rule is free to step; under the gallop rule it waits for its pair
        assertTrue(GaitType.WALK.canMoveLeg(legs.get(1)));
        assertFalse(GaitType.GALLOP.canMoveLeg(legs.get(1)));
    }

    @Test
    @DisplayName("leg look-up: sides, pairs and neighbours")
    void legLookUp() {
        assertTrue(LegLookUp.isLeftLeg(2));
        assertTrue(LegLookUp.isRightLeg(3));
        assertEquals(1, LegLookUp.getPairIndex(3));
        assertTrue(LegLookUp.isDiagonal1(0));
        assertTrue(LegLookUp.isDiagonal2(1));
        assertTrue(LegLookUp.isDiagonal1(3));
        assertTrue(LegLookUp.isDiagonal2(2));
        assertEquals(List.of(1, 5), LegLookUp.diagonal(2));
        assertEquals(List.of(0, 4), LegLookUp.diagonal(3));
        assertEquals(List.of(0, 4, 3), LegLookUp.adjacent(2));
        assertEquals(List.of(1, 5, 2), LegLookUp.adjacent(3));
    }
}
