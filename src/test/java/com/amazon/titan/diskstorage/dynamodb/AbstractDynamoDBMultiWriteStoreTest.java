/*
 * Copyright 2014-2017 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Portions copyright Titan: Distributed Graph Database - Copyright 2012 and onwards Aurelius.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazon.titan.diskstorage.dynamodb;

import static com.thinkaurelius.titan.diskstorage.keycolumnvalue.KeyColumnValueStore.NO_ADDITIONS;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazon.titan.TestGraphUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.thinkaurelius.titan.diskstorage.AbstractKCVSTest;
import com.thinkaurelius.titan.diskstorage.BackendException;
import com.thinkaurelius.titan.diskstorage.Entry;
import com.thinkaurelius.titan.diskstorage.KeyColumnValueStoreUtil;
import com.thinkaurelius.titan.diskstorage.MultiWriteKeyColumnValueStoreTest;
import com.thinkaurelius.titan.diskstorage.StaticBuffer;
import com.thinkaurelius.titan.diskstorage.configuration.BasicConfiguration;
import com.thinkaurelius.titan.diskstorage.configuration.WriteConfiguration;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.KCVMutation;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.KCVSUtil;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.KeyColumnValueStore;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.KeyColumnValueStoreManager;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.KeySliceQuery;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.StoreManager;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.StoreTransaction;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.cache.CacheTransaction;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.cache.KCVEntryMutation;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.cache.KCVSCache;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.cache.NoKCVSCache;
import com.thinkaurelius.titan.diskstorage.util.StaticArrayBuffer;
import com.thinkaurelius.titan.diskstorage.util.StaticArrayEntry;
import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration;

/**
*
* @author Alexander Patrikalakis
*
*/
public abstract class AbstractDynamoDBMultiWriteStoreTest extends AbstractKCVSTest {

    public static final String TEST_STORE1 = "testStore1";
    public static final String TEST_STORE2 = "testStore2";
    protected final BackendDataModel model;
    protected AbstractDynamoDBMultiWriteStoreTest(BackendDataModel model) {
        this.model = model;
    }

    public KeyColumnValueStoreManager openStorageManager() throws BackendException {
        final List<String> storeNames = new ArrayList<>(2);
        storeNames.add(TEST_STORE1);
        storeNames.add(TEST_STORE2);
        final WriteConfiguration wc = TestGraphUtil.instance().getStoreConfig(model, storeNames);
        final BasicConfiguration config = new BasicConfiguration(GraphDatabaseConfiguration.ROOT_NS, wc,
            BasicConfiguration.Restriction.NONE);

        return new DynamoDBStoreManager(config);
    }

    @AfterClass
    public static void cleanUpTables() throws Exception {
        TestGraphUtil.instance().cleanUpTables();
    }

    // begin copied code:
    // https://github.com/thinkaurelius/titan/blob/1.0.0/titan-test/src/main/java/com/thinkaurelius/titan/diskstorage/MultiWriteKeyColumnValueStoreTest.java#L31
    private Logger log = LoggerFactory.getLogger(MultiWriteKeyColumnValueStoreTest.class);

    int numKeys = 500;
    int numColumns = 50;

    int bufferSize = 20;

    protected String storeName1 = TEST_STORE1;
    private KCVSCache store1;
    protected String storeName2 = TEST_STORE2;
    private KCVSCache store2;


    public KeyColumnValueStoreManager manager;
    public StoreTransaction tx;


    private Random rand = new Random(10);

    @Before
    public void setUp() throws Exception {
        StoreManager m = openStorageManager();
        m.clearStorage();
        m.close();
        open();
    }

    @After
    public void tearDown() throws Exception {
        close();
    }

    public void open() throws BackendException {
        manager = openStorageManager();
        tx = new CacheTransaction(manager.beginTransaction(getTxConfig()), manager, bufferSize, Duration.ofMillis(100), true);
        store1 = new NoKCVSCache(manager.openDatabase(storeName1));
        store2 = new NoKCVSCache(manager.openDatabase(storeName2));

    }

    public void close() throws BackendException {
        if (tx != null) tx.commit();
        if (null != store1) store1.close();
        if (null != store2) store2.close();
        if (null != manager) manager.close();
    }

    public void newTx() throws BackendException {
        if (tx!=null) tx.commit();
        tx = new CacheTransaction(manager.beginTransaction(getTxConfig()), manager, bufferSize, Duration.ofMillis(100), true);
    }

    @Test
    public void deletionsAppliedBeforeAdditions() throws BackendException {

        StaticBuffer b1 = KeyColumnValueStoreUtil.longToByteBuffer(1);

        Assert.assertNull(KCVSUtil.get(store1, b1, b1, tx));

        List<Entry> additions = Lists.newArrayList(StaticArrayEntry.of(b1, b1));

        List<Entry> deletions = Lists.newArrayList(additions);

        Map<StaticBuffer, KCVEntryMutation> combination = new HashMap<StaticBuffer, KCVEntryMutation>(1);
        Map<StaticBuffer, KCVEntryMutation> deleteOnly = new HashMap<StaticBuffer, KCVEntryMutation>(1);
        Map<StaticBuffer, KCVEntryMutation> addOnly = new HashMap<StaticBuffer, KCVEntryMutation>(1);

        combination.put(b1, new KCVEntryMutation(additions, deletions));
        deleteOnly.put(b1, new KCVEntryMutation(KeyColumnValueStore.NO_ADDITIONS, deletions));
        addOnly.put(b1, new KCVEntryMutation(additions, KCVSCache.NO_DELETIONS));

        store1.mutateEntries(b1, additions, deletions, tx);
        newTx();

        StaticBuffer result = KCVSUtil.get(store1, b1, b1, tx);

        Assert.assertEquals(b1, result);

        store1.mutateEntries(b1, NO_ADDITIONS, deletions, tx);
        newTx();

        for (int i = 0; i < 100; i++) {
            StaticBuffer n = KCVSUtil.get(store1, b1, b1, tx);
            Assert.assertNull(n);
            store1.mutateEntries(b1, additions, KCVSCache.NO_DELETIONS, tx);
            newTx();
            store1.mutateEntries(b1, NO_ADDITIONS, deletions, tx);
            newTx();
            n = KCVSUtil.get(store1, b1, b1, tx);
            Assert.assertNull(n);
        }

        for (int i = 0; i < 100; i++) {
            store1.mutateEntries(b1, NO_ADDITIONS, deletions, tx);
            newTx();
            store1.mutateEntries(b1, additions, KCVSCache.NO_DELETIONS, tx);
            newTx();
            Assert.assertEquals(b1, KCVSUtil.get(store1, b1, b1, tx));
        }

        for (int i = 0; i < 100; i++) {
            store1.mutateEntries(b1, additions, deletions, tx);
            newTx();
            Assert.assertEquals(b1, KCVSUtil.get(store1, b1, b1, tx));
        }
    }

    @Test
    public void mutateManyWritesSameKeyOnMultipleCFs() throws BackendException {

        final long arbitraryLong = 42;
        assert 0 < arbitraryLong;

        final StaticBuffer key = KeyColumnValueStoreUtil.longToByteBuffer(arbitraryLong * arbitraryLong);
        final StaticBuffer val = KeyColumnValueStoreUtil.longToByteBuffer(arbitraryLong * arbitraryLong * arbitraryLong);
        final StaticBuffer col = KeyColumnValueStoreUtil.longToByteBuffer(arbitraryLong);
        final StaticBuffer nextCol = KeyColumnValueStoreUtil.longToByteBuffer(arbitraryLong + 1);

        final StoreTransaction directTx = manager.beginTransaction(getTxConfig());

        KCVMutation km = new KCVMutation(
            Lists.newArrayList(StaticArrayEntry.of(col, val)),
            Lists.<StaticBuffer>newArrayList());

        Map<StaticBuffer, KCVMutation> keyColumnAndValue = ImmutableMap.of(key, km);

        Map<String, Map<StaticBuffer, KCVMutation>> mutations =
            ImmutableMap.of(
                storeName1, keyColumnAndValue,
                storeName2, keyColumnAndValue);

        manager.mutateMany(mutations, directTx);

        directTx.commit();

        KeySliceQuery query = new KeySliceQuery(key, col, nextCol);
        List<Entry> expected = ImmutableList.of(StaticArrayEntry.of(col, val));

        Assert.assertEquals(expected, store1.getSlice(query, tx));
        Assert.assertEquals(expected, store2.getSlice(query, tx));

    }

    //update this for janus TODO
    @Test
    public void mutateManyStressTest() throws BackendException {
        this.mutateManyStressTestInner(1);
    }
    // update this for janus TODO
    protected void mutateManyStressTestInner(int rounds) throws BackendException {

        Map<StaticBuffer, Map<StaticBuffer, StaticBuffer>> state =
            new HashMap<StaticBuffer, Map<StaticBuffer, StaticBuffer>>();

        int dels = 1024;
        int adds = 4096;

        for (int round = 0; round < rounds; round++) { //TODO update janus
            Map<StaticBuffer, KCVEntryMutation> changes = mutateState(state, dels, adds);

            applyChanges(changes, store1, tx);
            applyChanges(changes, store2, tx);
            newTx();

            int deletesExpected = 0 == round ? 0 : dels;

            int stateSizeExpected = adds + (adds - dels) * round;

            Assert.assertEquals(stateSizeExpected, checkThatStateExistsInStore(state, store1, round));
            Assert.assertEquals(deletesExpected, checkThatDeletionsApplied(changes, store1, round));

            Assert.assertEquals(stateSizeExpected, checkThatStateExistsInStore(state, store2, round));
            Assert.assertEquals(deletesExpected, checkThatDeletionsApplied(changes, store2, round));
        }
    }

    public void applyChanges(Map<StaticBuffer, KCVEntryMutation> changes, KCVSCache store, StoreTransaction tx) throws BackendException {
        for (Map.Entry<StaticBuffer, KCVEntryMutation> change : changes.entrySet()) {
            store.mutateEntries(change.getKey(), change.getValue().getAdditions(), change.getValue().getDeletions(), tx);
        }
    }

    public int checkThatStateExistsInStore(Map<StaticBuffer, Map<StaticBuffer, StaticBuffer>> state, KeyColumnValueStore store, int round) throws BackendException {
        int checked = 0;

        for (StaticBuffer key : state.keySet()) {
            for (StaticBuffer col : state.get(key).keySet()) {
                StaticBuffer val = state.get(key).get(col);

                Assert.assertEquals(val, KCVSUtil.get(store, key, col, tx));

                checked++;
            }
        }

        log.debug("Checked existence of {} key-column-value triples on round {}", checked, round);

        return checked;
    }

    public int checkThatDeletionsApplied(Map<StaticBuffer, KCVEntryMutation> changes, KeyColumnValueStore store, int round) throws BackendException {
        int checked = 0;
        int skipped = 0;

        for (StaticBuffer key : changes.keySet()) {
            KCVEntryMutation m = changes.get(key);

            if (!m.hasDeletions())
                continue;

            List<Entry> deletions = m.getDeletions();

            List<Entry> additions = m.getAdditions();

            for (Entry entry : deletions) {
                StaticBuffer col = entry.getColumn();

                if (null != additions && additions.contains(StaticArrayEntry.of(col, col))) {
                    skipped++;
                    continue;
                }

                Assert.assertNull(KCVSUtil.get(store, key, col, tx));

                checked++;
            }
        }

        log.debug("Checked absence of {} key-column-value deletions on round {} (skipped {})", new Object[]{checked, round, skipped});

        return checked;
    }

    /**
     * Pseudorandomly change the supplied {@code state}.
     * <p/>
     * This method removes {@code min(maxDeletionCount, S)} entries from the
     * maps in {@code state.values()}, where {@code S} is the sum of the sizes
     * of the maps in {@code state.values()}; this method then adds
     * {@code additionCount} pseudorandomly generated entries spread across
     * {@code state.values()}, potentially adding new keys to {@code state}
     * since they are randomly generated. This method then returns a map of keys
     * to Mutations representing the changes it has made to {@code state}.
     *
     * @param state            Maps keys -> columns -> values
     * @param maxDeletionCount Remove at most this many entries from state
     * @param additionCount    Add exactly this many entries to state
     * @return A KCVMutation map
     */
    public Map<StaticBuffer, KCVEntryMutation> mutateState(
        Map<StaticBuffer, Map<StaticBuffer, StaticBuffer>> state,
        int maxDeletionCount, int additionCount) {

        final int keyLength = 8;
        final int colLength = 16;

        Map<StaticBuffer, KCVEntryMutation> result = new HashMap<StaticBuffer, KCVEntryMutation>();

        // deletion pass
        int dels = 0;

        StaticBuffer key = null, col = null;
        Entry entry = null;

        Iterator<StaticBuffer> keyIter = state.keySet().iterator();

        while (keyIter.hasNext() && dels < maxDeletionCount) {
            key = keyIter.next();

            Iterator<Map.Entry<StaticBuffer,StaticBuffer>> colIter =
                state.get(key).entrySet().iterator();

            while (colIter.hasNext() && dels < maxDeletionCount) {
                Map.Entry<StaticBuffer,StaticBuffer> colEntry = colIter.next();
                entry = StaticArrayEntry.of(colEntry.getKey(),colEntry.getValue());

                if (!result.containsKey(key)) {
                    KCVEntryMutation m = new KCVEntryMutation(new LinkedList<Entry>(),
                        new LinkedList<Entry>());
                    result.put(key, m);
                }

                result.get(key).deletion(entry);

                dels++;

                colIter.remove();

                if (state.get(key).isEmpty()) {
                    assert !colIter.hasNext();
                    keyIter.remove();
                }
            }
        }

        // addition pass
        for (int i = 0; i < additionCount; i++) {

            while (true) {
                byte keyBuf[] = new byte[keyLength];
                rand.nextBytes(keyBuf);
                key = new StaticArrayBuffer(keyBuf);

                byte colBuf[] = new byte[colLength];
                rand.nextBytes(colBuf);
                col = new StaticArrayBuffer(colBuf);

                if (!state.containsKey(key) || !state.get(key).containsKey(col)) {
                    break;
                }
            }

            if (!state.containsKey(key)) {
                Map<StaticBuffer, StaticBuffer> m = new HashMap<StaticBuffer, StaticBuffer>();
                state.put(key, m);
            }

            state.get(key).put(col, col);

            if (!result.containsKey(key)) {
                KCVEntryMutation m = new KCVEntryMutation(new LinkedList<Entry>(),
                    new LinkedList<Entry>());
                result.put(key, m);
            }

            result.get(key).addition(StaticArrayEntry.of(col, col));

        }

        return result;
    }

    public Map<StaticBuffer, KCVMutation> generateMutation(int keyCount, int columnCount, Map<StaticBuffer, KCVMutation> deleteFrom) {
        Map<StaticBuffer, KCVMutation> result = new HashMap<StaticBuffer, KCVMutation>(keyCount);

        Random keyRand = new Random(keyCount);
        Random colRand = new Random(columnCount);

        final int keyLength = 8;
        final int colLength = 6;

        Iterator<Map.Entry<StaticBuffer, KCVMutation>> deleteIter = null;
        List<Entry> lastDeleteIterResult = null;

        if (null != deleteFrom) {
            deleteIter = deleteFrom.entrySet().iterator();
        }

        for (int ik = 0; ik < keyCount; ik++) {
            byte keyBuf[] = new byte[keyLength];
            keyRand.nextBytes(keyBuf);
            StaticBuffer key = new StaticArrayBuffer(keyBuf);

            List<Entry> additions = new LinkedList<Entry>();
            List<StaticBuffer> deletions = new LinkedList<StaticBuffer>();

            for (int ic = 0; ic < columnCount; ic++) {

                boolean deleteSucceeded = false;
                if (null != deleteIter && 1 == ic % 2) {

                    if (null == lastDeleteIterResult || lastDeleteIterResult.isEmpty()) {
                        while (deleteIter.hasNext()) {
                            Map.Entry<StaticBuffer, KCVMutation> ent = deleteIter.next();
                            if (ent.getValue().hasAdditions() && !ent.getValue().getAdditions().isEmpty()) {
                                lastDeleteIterResult = ent.getValue().getAdditions();
                                break;
                            }
                        }
                    }


                    if (null != lastDeleteIterResult && !lastDeleteIterResult.isEmpty()) {
                        Entry e = lastDeleteIterResult.get(0);
                        lastDeleteIterResult.remove(0);
                        deletions.add(e.getColumn());
                        deleteSucceeded = true;
                    }
                }

                if (!deleteSucceeded) {
                    byte colBuf[] = new byte[colLength];
                    colRand.nextBytes(colBuf);
                    StaticBuffer col = new StaticArrayBuffer(colBuf);

                    additions.add(StaticArrayEntry.of(col, col));
                }

            }

            KCVMutation m = new KCVMutation(additions, deletions);

            result.put(key, m);
        }

        return result;
    }
    // end copied code
    // https://github.com/thinkaurelius/titan/blob/1.0.0/titan-test/src/main/java/com/thinkaurelius/titan/diskstorage/MultiWriteKeyColumnValueStoreTest.java#L431
}
