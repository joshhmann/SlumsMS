package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import client.Job;
import net.server.PlayerStorage;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotSupplies;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.server.SoloMaplingUtilities;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

class LifeFriendInterruptTest {
    @Test void aChosenFriendRequestCanEndAHangoutWithoutOverlappingIt() { check(false); }
    @Test void aNewCommitmentKeepsTheHangoutAndPreventsTheFriendStart() { check(true); }

    private void check(boolean held) {
        Map<String,String> before = new HashMap<>();
        for (String key : List.of("enabled","execute","metrics")) before.put(key,System.getProperty("slums.botlife."+key));
        var bot=mock(TrainingBot.class); var chr=mock(Character.class); var player=mock(Character.class);
        var map=mock(MapleMap.class); var world=mock(World.class);var storage=mock(PlayerStorage.class);
        when(bot.getChr()).thenReturn(chr);when(bot.getRunning()).thenReturn(true);
        when(chr.getId()).thenReturn(1000005001);when(chr.getLevel()).thenReturn(50);when(chr.getJob()).thenReturn(Job.WARRIOR);
        when(chr.getMap()).thenReturn(map);when(player.getMap()).thenReturn(map);when(player.getId()).thenReturn(42);
        when(map.getChannelId()).thenReturn(1);when(world.getPlayerStorage()).thenReturn(storage);
        when(storage.getCharacterById(42)).thenReturn(player);
        var queue=new LifeActivityQueue();long now=System.currentTimeMillis();
        var hangout=new Result(List.of(),Optional.of(Activity.HANG_OUT),Outcome.CHOOSE);
        var old=queue.prepare(hangout,now,30_000);queue.offer(old,now);
        queue.drain(now,true,()->true,new LifeAcceptance.Executor(){
            public boolean supports(Activity a){return true;}
            public Optional<LifeAcceptance.Accepted> start(Activity a){return Optional.of(new LifeAcceptance.Accepted(a,"staying here"));}
        });
        when(bot.lifeActivityState()).thenAnswer(c->queue.state());
        when(bot.freeForAutomaticLifeActivity(anyLong())).thenReturn(!held);
        when(bot.canStartLifeActivity(any(),anyLong())).thenAnswer(c->c.getArgument(0)==Activity.HANG_OUT||c.getArgument(0)==Activity.HELP_FRIEND);
        when(bot.interruptLifeDwell(anyLong())).thenAnswer(c->queue.finish(c.getArgument(0),LifeActivityQueue.End.CANCELLED));
        when(bot.prepareLifeActivity(any())).thenAnswer(c->queue.prepare(c.getArgument(0),now,30_000));
        when(bot.submitLifeActivity(any())).thenAnswer(c->queue.offer(c.getArgument(0),now));
        var snapshot=new LifeInspection.Snapshot("Fixture",50,100000000,"TrainingBot","RUNNING",10,10,.1,false,false);
        var context=new LifePolicy.Context(new Needs(Map.of()),Map.of(Activity.HANG_OUT,"here",Activity.HELP_FRIEND,"friend"),
                0,0,0,false,false,false,new Current(Activity.HANG_OUT,now-1000,600_000,0),List.of(),List.of());
        var profile=LifeProfile.forBot(chr.getId());
        var report=new LifeObservation.Report(snapshot,profile,context,LifePolicy.preview(context,profile,now),queue.state());
        try(var roster=mockStatic(BotRoster.class);var affinity=mockStatic(BotAffinity.class);
            var utilities=mockStatic(SoloMaplingUtilities.class);var observation=mockStatic(LifeObservation.class);
            var attack=mockStatic(BotAttackDriver.class);var supplies=mockStatic(BotSupplies.class)) {
            System.setProperty("slums.botlife.enabled","true");System.setProperty("slums.botlife.execute","true");
            System.setProperty("slums.botlife.metrics","false");
            utilities.when(SoloMaplingUtilities::world).thenReturn(world);
            affinity.when(()->BotAffinity.friend(chr.getId(),42)).thenReturn(true);
            affinity.when(BotAffinity::knownPlayers).thenReturn(List.of());
            observation.when(()->LifeObservation.weaponLevel(chr)).thenReturn(OptionalInt.empty());
            observation.when(()->LifeObservation.capture(bot,now)).thenReturn(report);
            var runtime=new LifeRuntime(bot);runtime.phase("LIFE_DWELL",now);
            assertTrue(runtime.friendAsk(player,false,now));runtime.tick(now);
            if(held){
                assertEquals(old.number(),queue.state().active());verify(bot,never()).interruptLifeDwell(anyLong());
                verify(bot,never()).submitLifeActivity(any());
            }else{
                var chosen=org.mockito.ArgumentCaptor.forClass(Result.class);verify(bot).prepareLifeActivity(chosen.capture());
                assertEquals(Activity.HELP_FRIEND,chosen.getValue().selected().orElseThrow());
                assertEquals(0,queue.state().active());assertTrue(queue.hasWaiting());
                assertEquals(LifeActivityQueue.End.CANCELLED,queue.state().end());
            }
        }finally{before.forEach((key,value)->{if(value==null)System.clearProperty("slums.botlife."+key);else System.setProperty("slums.botlife."+key,value);});}
    }
}
