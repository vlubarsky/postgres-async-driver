/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.pgasync.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.github.pgasync.Connection;
import com.github.pgasync.ResultSet;
import com.github.pgasync.Transaction;
import com.github.pgasync.callback.ErrorHandler;
import com.github.pgasync.callback.ResultHandler;
import com.github.pgasync.callback.TransactionCompletedHandler;
import com.github.pgasync.callback.TransactionHandler;

/**
 * Tests for BEGIN/COMMIT/ROLLBACK.
 * 
 * @author Antti Laisi
 */
public class TransactionTest extends ConnectedTestBase {

    final ErrorHandler err = new ErrorHandler() {
        @Override
        public void onError(Throwable t) {
            throw new AssertionError("failed", t);
        }
    };
    final ResultHandler fail = new ResultHandler() {
        @Override
        public void onResult(ResultSet result) {
            fail();
        }
    };

    @BeforeClass
    public static void create() {
        query("CREATE TABLE TX_TEST(ID INT8 PRIMARY KEY)");
    }

    @AfterClass
    public static void drop() {
        query("DROP TABLE IF EXISTS TX_TEST");
    }

    @Test
    public void shouldCommitSelectInTransaction() throws Exception {
        final CountDownLatch sync = new CountDownLatch(1);

        connection().begin(new TransactionHandler() {
            @Override
            public void onBegin(Connection txconn, final Transaction transaction) {
                txconn.query("SELECT 1", new ResultHandler() {
                    @Override
                    public void onResult(ResultSet result) {
                        assertEquals(1L, result.get(0).getLong(0).longValue());
                        transaction.commit(new TransactionCompletedHandler() {
                            @Override
                            public void onComplete() {
                                sync.countDown();
                            }
                        }, err);
                    }
                }, err);
            }
        }, err);

        assertTrue(sync.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void shouldCommitInsertInTransaction() throws Exception {
        final CountDownLatch sync = new CountDownLatch(1);

        connection().begin(new TransactionHandler() {
            @Override
            public void onBegin(Connection txconn, final Transaction transaction) {
                txconn.query("INSERT INTO TX_TEST(ID) VALUES(10)", new ResultHandler() {
                    @Override
                    public void onResult(ResultSet result) {
                        assertEquals(1, result.updatedRows());
                        transaction.commit(new TransactionCompletedHandler() {
                            @Override
                            public void onComplete() {
                                sync.countDown();
                            }
                        }, err);
                    }
                }, err);
            }
        }, err);

        assertTrue(sync.await(5, TimeUnit.SECONDS));
        assertEquals(10L, query("SELECT ID FROM TX_TEST WHERE ID = 10").get(0).getLong(0).longValue());
    }

    @Test
    public void shouldRollbackTransaction() throws Exception {
        final CountDownLatch sync = new CountDownLatch(1);

        connection().begin(new TransactionHandler() {
            @Override
            public void onBegin(Connection txconn, final Transaction transaction) {
                txconn.query("INSERT INTO TX_TEST(ID) VALUES(9)", new ResultHandler() {
                    @Override
                    public void onResult(ResultSet result) {
                        assertEquals(1, result.updatedRows());
                        transaction.rollback(new TransactionCompletedHandler() {
                            @Override
                            public void onComplete() {
                                sync.countDown();
                            }
                        }, err);
                    }
                }, err);
            }
        }, err);

        assertTrue(sync.await(5, TimeUnit.SECONDS));
        assertEquals(0L, query("SELECT ID FROM TX_TEST WHERE ID = 9").size());
    }

    @Test
    public void shouldRollbackTransactionOnBackendError() throws Exception {
        final CountDownLatch sync = new CountDownLatch(1);

        connection().begin(new TransactionHandler() {
            @Override
            public void onBegin(final Connection txconn, final Transaction transaction) {
                txconn.query("INSERT INTO TX_TEST(ID) VALUES(11)", new ResultHandler() {
                    @Override
                    public void onResult(ResultSet result) {
                        assertEquals(1, result.updatedRows());
                        txconn.query("INSERT INTO TX_TEST(ID) VALUES(11)", fail, new ErrorHandler() {
                            @Override
                            public void onError(Throwable t) {
                                sync.countDown();
                            }
                        });
                    }
                }, err);
            }
        }, err);

        assertTrue(sync.await(5, TimeUnit.SECONDS));
        assertEquals(0, query("SELECT ID FROM TX_TEST WHERE ID = 11").size());
    }
    
    @Test
    public void shouldInvalidateTxConnAfterError() throws Exception {
        final CountDownLatch sync = new CountDownLatch(1);

        connection().begin(new TransactionHandler() {
            @Override
            public void onBegin(final Connection txconn, final Transaction transaction) {
                txconn.query("INSERT INTO TX_TEST(ID) VALUES(22)", new ResultHandler() {
                    @Override
                    public void onResult(ResultSet result) {
                        assertEquals(1, result.updatedRows());
                        txconn.query("INSERT INTO TX_TEST(ID) VALUES(22)", fail, new ErrorHandler() {
                            @Override
                            public void onError(Throwable t) {
                                txconn.query("SELECT 1", fail, new ErrorHandler() {
                                    @Override
                                    public void onError(Throwable t) {
                                        assertEquals("Transaction is rolled back", t.getMessage());
                                        sync.countDown();
                                    }
                                });
                            }
                        });
                    }
                }, err);
            }
        }, err);

        assertTrue(sync.await(5, TimeUnit.SECONDS));
        assertEquals(0, query("SELECT ID FROM TX_TEST WHERE ID = 22").size());
    }
}
