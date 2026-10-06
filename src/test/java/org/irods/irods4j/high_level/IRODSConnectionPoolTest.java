package org.irods.irods4j.high_level;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.irods.irods4j.authentication.NativeAuthPlugin;
import org.irods.irods4j.common.JsonUtil;
import org.irods.irods4j.common.XmlUtil;
import org.irods.irods4j.high_level.administration.IRODSUsers;
import org.irods.irods4j.high_level.administration.IRODSUsers.User;
import org.irods.irods4j.high_level.administration.IRODSUsers.UserType;
import org.irods.irods4j.high_level.administration.IRODSZones.ZoneType;
import org.irods.irods4j.high_level.connection.IRODSConnection;
import org.irods.irods4j.high_level.connection.IRODSConnectionPool;
import org.irods.irods4j.high_level.connection.IRODSConnectionPool.PoolConnection;
import org.irods.irods4j.high_level.connection.QualifiedUsername;
import org.irods.irods4j.high_level.io.IRODSDataObjectInputStream;
import org.irods.irods4j.high_level.io.IRODSDataObjectOutputStream;
import org.irods.irods4j.high_level.vfs.IRODSFilesystem;
import org.irods.irods4j.high_level.vfs.IRODSFilesystem.RemoveOptions;
import org.irods.irods4j.low_level.api.IRODSApi;
import org.irods.irods4j.low_level.api.IRODSException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class IRODSConnectionPoolTest {

	static final Logger log = LogManager.getLogger();

	static String host = "localhost";
	static int port = 1247;
	static String zone = "tempZone";
	static String username = "rods";
	static String password = "rods";
	static String clientUserName = "baloust";
    static final String TEST_COLLECTION = "/%s/home/%s/test_proxy".formatted(zone, clientUserName);
    static final String TEST_OBJECT = TEST_COLLECTION + "/secret_test.txt";

	@BeforeAll
	static void setUpBeforeClass() throws Exception {
		XmlUtil.enablePrettyPrinting();
		JsonUtil.enablePrettyPrinting();
		
		// Create the user clientUser. 
		try (var conn = new IRODSConnection()) {
			conn.connect(host, port, new QualifiedUsername(username, zone));
			conn.authenticate(new NativeAuthPlugin(), password);
			var comm = conn.getRcComm();
			User user = new User(clientUserName, Optional.of(zone));
			if (!IRODSUsers.exists(comm, user)) {
				IRODSUsers.addUser(comm, user, UserType.RODSUSER, ZoneType.LOCAL);
			}
		}

		// Create the test file as clientUser so that he has READ permission.
        try (var conn = new IRODSConnection()) {
            conn.connect(host, port, new QualifiedUsername(username, zone), new QualifiedUsername(clientUserName, zone));
            conn.authenticate(new NativeAuthPlugin(), password);
            var comm = conn.getRcComm();

            if (!IRODSFilesystem.isCollection(comm, TEST_COLLECTION)) {
                IRODSFilesystem.createCollection(comm, TEST_COLLECTION, "/%s/home/%s".formatted(zone, clientUserName));
            }

            if (!IRODSFilesystem.isDataObject(comm, TEST_OBJECT)) {
                try (var out = new IRODSDataObjectOutputStream(comm, TEST_OBJECT, true, false)) {
                    out.write("données de test".getBytes(StandardCharsets.UTF_8));
                }
            }
        }
	}

	@AfterAll
	static void tearDownAfterClass() throws Exception {
		XmlUtil.disablePrettyPrinting();
		JsonUtil.disablePrettyPrinting();

		// Remove the test file.
		try (var conn = new IRODSConnection()) {
			conn.connect(host, port, new QualifiedUsername(username, zone),
					new QualifiedUsername(clientUserName, zone));
			conn.authenticate(new NativeAuthPlugin(), username);
			var comm = conn.getRcComm();

			if (IRODSFilesystem.isDataObject(comm, TEST_OBJECT)) {
				try {
					IRODSFilesystem.remove(comm, TEST_OBJECT, RemoveOptions.NO_TRASH);
				} catch (Exception e) {
					// nothing to do
				}
			}

			if (IRODSFilesystem.isCollection(comm, TEST_COLLECTION)) {
				try {
					IRODSFilesystem.remove(comm, TEST_COLLECTION, RemoveOptions.NO_TRASH);
				} catch (Exception e) {
					// nothing to do
				}
			}
		}

		// Remove the user clientUser. 
		try (var conn = new IRODSConnection()) {
			conn.connect(host, port, new QualifiedUsername(username, zone));
			conn.authenticate(new NativeAuthPlugin(), username);
			var comm = conn.getRcComm();
			try {
				User user = new User(clientUserName, Optional.of(zone));
				IRODSUsers.removeUser(comm, user);
			} catch (Exception e) {
				// Ignore
			}
		}
	}

	@Test
	void testSynchronousCreationOfPoolWithMultipleConnections() throws Exception {
		try (var pool = new IRODSConnectionPool(10)) {
			pool.start(host, port, new QualifiedUsername(username, zone), comm -> {
				try {
					IRODSApi.rcAuthenticateClient(comm, new NativeAuthPlugin(), password);

					// Returning true lets the connection pool know that authentication was
					// successful.
					return true;
				} catch (Exception e) {
					// Returning false lets the connection pool know that authentication failed.
					// This means the connection pool must not be used.
					return false;
				}
			});

			var homeCollection = String.format("/%s/home/%s", zone, username);
			for (int i = 0; i < 30; ++i) {
				try (var conn = pool.getConnection()) {
					assertTrue(IRODSFilesystem.isCollection(conn.getRcComm(), homeCollection));
				}
			}
		}
	}

	@Test
	void testAsynchronousCreationOfPoolWithMultipleConnections() throws Exception {
		try (var pool = new IRODSConnectionPool(10)) {
			// Create a thread pool containing 5 threads.
			var threadPool = Executors.newFixedThreadPool(5);

			// Use the thread pool to speed up the connection process.
			pool.start(threadPool, host, port, new QualifiedUsername(username, zone), comm -> {
				try {
					IRODSApi.rcAuthenticateClient(comm, new NativeAuthPlugin(), password);

					// Returning true lets the connection pool know that authentication was
					// successful.
					return true;
				} catch (Exception e) {
					// Returning false lets the connection pool know that authentication failed.
					// This means the connection pool must not be used.
					return false;
				}
			});

			var homeCollection = String.format("/%s/home/%s", zone, username);
			for (int i = 0; i < 30; ++i) {
				try (var conn = pool.getConnection()) {
					assertTrue(IRODSFilesystem.isCollection(conn.getRcComm(), homeCollection));
				}
			}

			threadPool.shutdown();
		}
	}

	@Test
	void testBadHostResultsInExceptionBeingThrown() throws Exception {
		assertThrows(IllegalStateException.class, () -> {
			try (var pool = new IRODSConnectionPool(1)) {
				pool.start("INVALID_HOST", port, new QualifiedUsername(username, zone), comm -> {
					try {
						IRODSApi.rcAuthenticateClient(comm, new NativeAuthPlugin(), password);
						return true;
					} catch (Exception e) {
						return false;
					}
				});
			}
		});
	}

	@Test
	void testBadPortResultsInExceptionBeingThrown() throws Exception {
		assertThrows(IllegalStateException.class, () -> {
			try (var pool = new IRODSConnectionPool(1)) {
				pool.start(host, 9000, new QualifiedUsername(username, zone), comm -> {
					try {
						IRODSApi.rcAuthenticateClient(comm, new NativeAuthPlugin(), password);
						return true;
					} catch (Exception e) {
						return false;
					}
				});
			}
		});
	}

	@Test
	void testBadAuthSchemeResultsInExceptionBeingThrown() throws Exception {
		assertThrows(IllegalStateException.class, () -> {
			try (var pool = new IRODSConnectionPool(1)) {
				pool.start(host, port, new QualifiedUsername(username, zone), comm -> {
					try {
						IRODSApi.rcAuthenticateClient(comm, null, password);
						return true;
					} catch (Exception e) {
						return false;
					}
				});
			}
		});
	}

	/**
	 * Verifies that the new start() overload accepting a proxyUser and a
	 * clientUser works in synchronous mode.
	 *
	 * The proxy user "rods" is a rodsadmin. The client user "rods" is used here
	 * because it is the only user guaranteed to exist in the test environment.
	 * In a real deployment, the client user would differ from the proxy.
	 */
	@Test
	void testSynchronousPoolStartWithProxyUser() throws Exception {
		var proxyUser = new QualifiedUsername(username, zone);
		var clientUser = new QualifiedUsername(username, zone); // same user for the test

		try (var pool = new IRODSConnectionPool(5)) {
			pool.start(host, port, proxyUser, clientUser, comm -> {
				try {
					IRODSApi.rcAuthenticateClient(comm, new NativeAuthPlugin(), password);
					return true;
				} catch (Exception e) {
					return false;
				}
			});

			var homeCollection = String.format("/%s/home/%s", zone, username);
			for (int i = 0; i < 15; ++i) {
				try (var conn = pool.getConnection()) {
					assertNotNull(conn.getRcComm());
					assertTrue(IRODSFilesystem.isCollection(conn.getRcComm(), homeCollection));
				}
			}
		}
	}

	/**
	 * Verifies that the new start() overload accepting a proxyUser and a
	 * clientUser works in asynchronous mode.
	 */
	@Test
	void testAsynchronousPoolStartWithProxyUser() throws Exception {
		var proxyUser = new QualifiedUsername(username, zone);
		var clientUser = new QualifiedUsername(username, zone);

		var threadPool = Executors.newFixedThreadPool(5);
		try (var pool = new IRODSConnectionPool(10)) {
			pool.start(threadPool, host, port, proxyUser, clientUser, comm -> {
				try {
					IRODSApi.rcAuthenticateClient(comm, new NativeAuthPlugin(), password);
					return true;
				} catch (Exception e) {
					return false;
				}
			});

			var homeCollection = String.format("/%s/home/%s", zone, username);
			for (int i = 0; i < 15; ++i) {
				try (var conn = pool.getConnection()) {
					assertNotNull(conn.getRcComm());
					assertTrue(IRODSFilesystem.isCollection(conn.getRcComm(), homeCollection));
				}
			}
		} finally {
			threadPool.shutdown();
		}
	}

	/**
	 * Verifies that the start(host, port, clientUser, callback) signature
	 * still works.
	 */
	@Test
	void testStartSignatureStillWorks() throws Exception {
		try (var pool = new IRODSConnectionPool(3)) {
			pool.start(host, port, new QualifiedUsername(username, zone), comm -> {
				try {
					IRODSApi.rcAuthenticateClient(comm, new NativeAuthPlugin(), password);
					return true;
				} catch (Exception e) {
					return false;
				}
			});

			try (var conn = pool.getConnection()) {
				assertNotNull(conn.getRcComm());
				assertTrue(conn.isValid());
			}
		}
	}

	/**
	 * Verifies that a null proxyUser is rejected.
	 */
	@Test
	void testNullProxyUserIsRejected() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> {
			try (var pool = new IRODSConnectionPool(3)) {
				pool.start(host, port, null, new QualifiedUsername(username, zone), comm -> {
					try {
						IRODSApi.rcAuthenticateClient(comm, new NativeAuthPlugin(), password);
						return true;
					} catch (Exception e) {
						return false;
					}
				});
			}
		});
	}

	/**
	 * Verifies that an invalid proxyUser host triggers an
	 * IllegalStateException.
	 */
	@Test
	void testBadHostWithProxyUserResultsInException() throws Exception {
		var proxyUser = new QualifiedUsername(username, zone);
		var clientUser = new QualifiedUsername(clientUserName, zone);

		assertThrows(IllegalStateException.class, () -> {
			try (var pool = new IRODSConnectionPool(1)) {
				pool.start("INVALID_HOST", port, proxyUser, clientUser, comm -> {
					try {
						IRODSApi.rcAuthenticateClient(comm, new NativeAuthPlugin(), password);
						return true;
					} catch (Exception e) {
						return false;
					}
				});
			}
		});
	}

	/**
	 * Verifies that a connection returned by the pool with proxy support is
	 * usable and valid.
	 */
	@Test
	void testPooledConnectionWithProxyIsValid() throws Exception {
		var proxyUser = new QualifiedUsername(username, zone);
		var clientUser = new QualifiedUsername(clientUserName, zone);

		try (var pool = new IRODSConnectionPool(4)) {
			pool.start(host, port, proxyUser, clientUser, comm -> {
				try {
					IRODSApi.rcAuthenticateClient(comm, new NativeAuthPlugin(), password);
					return true;
				} catch (Exception e) {
					return false;
				}
			});

			for (int i = 0; i < 10; ++i) {
				try (PoolConnection conn = pool.getConnection()) {
					assertTrue(conn.isValid(), "Connection should be valid");
					assertNotNull(conn.getRcComm(), "RcComm should not be null");
				}
			}
		}
	}
	
	@Test
	void testProxyUserEnforcesClientPermissions() throws Exception {
	    var proxyUser = new QualifiedUsername(username, "tempZone");
	    var clientUser = new QualifiedUsername(clientUserName, "tempZone");
	    String objectPath = TEST_OBJECT;

	    try (var pool = new IRODSConnectionPool(2)) {
	        // Authentification with PROXY user (rods).
	        pool.start(host, port, proxyUser, clientUser, comm -> {
	            try {
					IRODSApi.rcAuthenticateClient(comm, new NativeAuthPlugin(), password);
				} catch (Exception e) {
					return false;
				}
	            return true;
	        });

	        try (var conn = pool.getConnection()) {
	        	// The pool should have switched over to clientUser.
	        	// clientUser has "read", so the read MUST SUCCEED.
	            assertDoesNotThrow(() -> {
	                try (var in = new IRODSDataObjectInputStream(conn.getRcComm(), objectPath)) {
	                    byte[] buffer = new byte[1024];
	                    in.read(buffer);
	                }
	            }, "%s should be able to read the file".formatted(clientUserName));
	        }
	    }
	}
	
	@Test
	void testRodsAdminWithoutProxyCannotReadSpecificUserDataObject() throws Exception {
		// Direct connection as rods, without a pool and without a proxy user.
		// rods does NOT have "read" permission.
		var clientUser = new QualifiedUsername(username, "tempZone");
		String objectPath = TEST_OBJECT;

		try (var pool = new IRODSConnectionPool(2)) {
			// Authentification with user (rods).
			pool.start(host, port, clientUser, comm -> {
				try {
					IRODSApi.rcAuthenticateClient(comm, new NativeAuthPlugin(), password);
				} catch (Exception e) {
					return false;
				}
				return true;
			});

			try (var conn = pool.getConnection()) {
				// rods CANNOT open the replica because it does not have "read" permission.
				// Fails with -358000 (rcReplicaOpen error).
				var ex = assertThrows(IRODSException.class, () -> {
					try (var in = new IRODSDataObjectInputStream(conn.getRcComm(), objectPath)) {
						in.read();
					}
				}, "rods should NOT be able to open the replica without read permission");

				// Verify that the error is really a replica-open error.
				assertTrue(
						ex.getMessage().contains("-358000") || ex.getMessage().contains("rcReplicaOpen"),
						"Expected replica open error, got: " + ex.getMessage()
						);
			}
		}
	}

}