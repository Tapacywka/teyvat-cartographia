package net.tapacywka.teyvatcartographia.map.data;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The set of plants shown is built once, also when several chunk workers ask for it at the same moment. */
class PlantsBuiltOnceTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void workersAskingTogetherGetOneSet() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 20; round++) {
                Plants.worldOpened();
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Set<Block>>> asked = new ArrayList<>();
                for (int i = 0; i < 8; i++) asked.add(pool.submit(() -> {
                    go.await();
                    return Plants.shownSet();
                }));
                go.countDown();
                Set<Set<Block>> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Future<Set<Block>> f : asked) distinct.add(f.get(30, TimeUnit.SECONDS));
                assertEquals(1, distinct.size(), "round " + round + ": every worker the same set");
                assertFalse(distinct.iterator().next().isEmpty(), "the game's plants are in it");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
