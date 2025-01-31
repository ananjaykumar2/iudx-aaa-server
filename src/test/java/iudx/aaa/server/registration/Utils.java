package iudx.aaa.server.registration;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Tuple;
import iudx.aaa.server.apiserver.RoleStatus;
import iudx.aaa.server.apiserver.Roles;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.RandomStringUtils;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static iudx.aaa.server.policy.Constants.itemTypes;
import static iudx.aaa.server.registration.Constants.CLIENT_SECRET_BYTES;
import static iudx.aaa.server.registration.Constants.DEFAULT_CLIENT;
import static iudx.aaa.server.registration.Constants.NIL_PHONE;
import static iudx.aaa.server.registration.Constants.NIL_UUID;
import static iudx.aaa.server.registration.Constants.SQL_CREATE_CLIENT;
import static iudx.aaa.server.registration.Constants.SQL_CREATE_ROLE;
import static iudx.aaa.server.registration.Constants.SQL_CREATE_USER;

/** Class to help manage mock user, organization, delegation creation and deletion etc. */
public class Utils {

  /* SQL queries for creating and deleting required data */
  public static final String SQL_CREATE_ORG =
      "INSERT INTO organizations (name, url, created_at, updated_at) "
          + "VALUES ($1:: text, $2::text, NOW(), NOW()) RETURNING id";

  public static final String SQL_DELETE_USRS = "DELETE FROM users WHERE organization_id = $1::uuid";

  public static final String SQL_DELETE_ORG = "DELETE FROM organizations WHERE id = $1::uuid";

  public static final String SQL_DELETE_CONSUMERS =
      "DELETE FROM users WHERE email_hash LIKE $1::text || '%'";
  public static final String SQL_CREATE_ADMIN_SERVER =
      "INSERT INTO resource_server (name, owner_id, url, created_at, updated_at) "
          + "VALUES ($1::text, $2::uuid, $3::text, NOW(), NOW())";
  public static final String SQL_DELETE_SERVERS =
      "DELETE FROM resource_server WHERE url = ANY ($1::text[])";
  public static final String SQL_DELETE_BULK_ORG =
      "DELETE FROM organizations WHERE id = ANY ($1::uuid[])";
  public static final String SQL_GET_SERVER_IDS =
      "SELECT id, url FROM resource_server WHERE url = ANY($1::text[])";
  public static final String SQL_CREATE_DELEG =
      "INSERT INTO delegations "
          + "(owner_id, user_id, resource_server_id,status, created_at, updated_at) "
          + "VALUES ($1::uuid, $2::uuid, $3::uuid, $4::"
          + "policy_status_enum, NOW(), NOW())"
          + " RETURNING id, resource_server_id";
  public static final String SQL_GET_DELEG_IDS =
      "SELECT d.id, url FROM delegations AS d JOIN "
          + "resource_server ON d.resource_server_id = resource_server.id"
          + " WHERE url = ANY($1::text[]) AND d.owner_id = $2::uuid";
  public static final String SQL_CREATE_APD =
      "INSERT INTO apds (id, name, url, owner_id, status, created_at, updated_at) VALUES "
          + "($1::uuid, $2::text, $3::text, $4::uuid, $5::apd_status_enum, NOW(), NOW()) ";
  private static final String SQL_DELETE_USER_BY_ID =
      "DELETE FROM users WHERE id = ANY($1::uuid[])";
  private static final String SQL_CREATE_RES_SERVER =
      "INSERT INTO resource_server(id,name,owner_id,url,created_at,updated_at) "
          + "Values ($1::uuid,$2::text,$3::uuid,$4::text,NOW(),NOW())";

  private static final String SQL_CREATE_RES_GRP =
      "INSERT INTO resource_group(id,cat_id,provider_id,resource_server_id,created_at,updated_at) "
          + "Values ($1::uuid,$2::text,$3::uuid,$4::uuid,NOW(),NOW())";

  private static final String SQL_CREATE_RES =
      "INSERT INTO resource(id,cat_id,provider_id,resource_group_id,created_at,updated_at,"
          + "resource_server_id) Values ($1::uuid,$2::text,$3::uuid,$4::uuid,NOW(),NOW(),$5::UUID)";

  private static final String SQL_DELETE_RESOURCE_BY_OWNER_ID =
      "DELETE FROM resource WHERE provider_id = ANY($1::uuid[])";
  private static final String SQL_DELETE_RESOURCE_GRP_BY_OWNER_ID =
      "DELETE FROM resource_group WHERE provider_id = ANY($1::uuid[])";
  private static final String SQL_DELETE_RESOURCE_SERVER_BY_OWNER_ID =
      "DELETE FROM resource_server WHERE owner_id = ANY($1::uuid[])";

  public static final String SQL_CREATE_NOTIFICATION =
      "INSERT INTO access_requests (id, user_id, item_id, item_type, owner_id, status,"
      + " expiry_duration, constraints, created_at, updated_at) VALUES"
      + " ($1::UUID, $2::UUID, $3::UUID, $4::item_enum, $5::UUID, $6::acc_reqs_status_enum,"
      + " $7::interval, $8::jsonb, NOW(), NOW())";
  
  public static final String SQL_CREATE_VALID_AUTH_POLICY = "INSERT INTO policies (owner_id, item_id,"
      + " item_type, user_id, status, expiry_time, constraints, created_at, updated_at)"
      + " SELECT owner_id, id, 'RESOURCE_SERVER'::item_enum, $1::uuid, 'ACTIVE'::policy_status_enum,"
      + " NOW() + INTERVAL '5 years', '{}'::jsonb, NOW(), NOW() FROM resource_server WHERE url = $2::text";
  
  public static final String SQL_DELETE_ANY_POLICIES = "UPDATE policies SET status = 'DELETED' WHERE"
      + " owner_id = ANY($1::uuid[]) OR user_id = ANY($1::uuid[])";

  private static final String SQL_CREATE_POLICY =
      "INSERT INTO policies (owner_id, item_id,"
          + " item_type, user_id, status, expiry_time, constraints, created_at, updated_at)"
          + "VALUES ($1::UUID, $2::UUID,$3::item_enum,$4::UUID,'ACTIVE'::policy_status_enum,"
          + "NOW() + INTERVAL '5 years','{}',NOW(),NOW())";

  private static final String SQL_CREATE_DELEGATE =
      "INSERT INTO delegations (owner_id,user_id,resource_server_id,status,created_at, updated_at)"
          + "VALUES ($1::UUID, $2::UUID,$3::UUID,'ACTIVE'::policy_status_enum,NOW(),NOW())";

  public static final String SQL_DELETE_DELEGATE =
      "UPDATE delegations SET status ='DELETED' where owner_id = ANY($1::uuid[])";

  /**
   * Create a mock user based on the supplied params. The user is created and the information of the
   * user is returned in a JsonObject. The fields returned are: <b>firstName, lastName, orgId, url,
   * email, clientId, clientSecret, keycloakId, userId, phone </b>
   *
   * @param pool Postgres PgPool connection to make DB calls
   * @param orgId organization ID. If no org is desired, send NIL_UUID
   * @param orgUrl the url of the given orgId. Send the URL of the created organization or send an
   *     empty string to get a gmail user. If a proper email is desired, send the url of an existing
   *     organization, but keep orgId as NIL_UUID
   * @param roleMap map of Roles to RoleStatus to set for the user
   * @param needPhone if true, phone number is assigned
   * @return a Future of JsonObject containing user information
   */
  public static Future<JsonObject> createFakeUser(
      PgPool pool, String orgId, String orgUrl, Map<Roles, RoleStatus> roleMap, Boolean needPhone) {

    Promise<JsonObject> response = Promise.promise();
    JsonObject resp = new JsonObject();
    resp.put("firstName", RandomStringUtils.randomAlphabetic(10).toLowerCase());
    resp.put("lastName", RandomStringUtils.randomAlphabetic(10).toLowerCase());

    String email;
    String phone;

    if (needPhone) {
      phone = "9" + RandomStringUtils.randomNumeric(9);
    } else {
      phone = NIL_PHONE;
    }
    resp.put("phone", phone);

    String orgIdToSet;

    if (orgId.toString() == NIL_UUID) {
      orgIdToSet = null;
      resp.put("orgId", null);
    } else {
      orgIdToSet = orgId.toString();
      resp.put("orgId", orgId);
    }

    if (orgUrl == "") {
      resp.put("url", null);
      email = RandomStringUtils.randomAlphabetic(10).toLowerCase() + "@gmail.com";
    } /* consumer may want a email with generated domain, but not be associated with an org */ else {
      resp.put("url", orgUrl);
      email = RandomStringUtils.randomAlphabetic(10).toLowerCase() + "@" + orgUrl;
    }

    resp.put("email", email);

    UUID clientId = UUID.randomUUID();
    SecureRandom random = new SecureRandom();
    byte[] randBytes = new byte[CLIENT_SECRET_BYTES];
    random.nextBytes(randBytes);
    String clientSecret = Hex.encodeHexString(randBytes);

    UUID keycloakId = UUID.randomUUID();

    resp.put("clientId", clientId.toString());
    resp.put("clientSecret", clientSecret.toString());
    resp.put("keycloakId", keycloakId.toString());

    Promise<UUID> genUserId = Promise.promise();

    Function<String, Tuple> createUserTup =
        (emailId) -> {
          String hash = DigestUtils.sha1Hex(emailId.getBytes());
          String emailHash = emailId.split("@")[1] + '/' + hash;
          return Tuple.of(phone, orgIdToSet, emailHash, keycloakId);
        };

    /*
     * Function to complete generated User ID promise and to create list of tuples for role creation
     * batch query
     */
    Function<UUID, List<Tuple>> createRoleTup =
        (id) -> {
          genUserId.complete(id);
          return roleMap.entrySet().stream()
              .map(p -> Tuple.of(id, p.getKey().name(), p.getValue().name()))
              .collect(Collectors.toList());
        };

    /* Function to hash client secret, and create tuple for client creation query */
    Supplier<Tuple> createClientTup =
        () -> {
          String hashedClientSecret = DigestUtils.sha512Hex(clientSecret);

          return Tuple.of(
              genUserId.future().result(), clientId, hashedClientSecret, DEFAULT_CLIENT);
        };

    pool.withTransaction(
            conn ->
                conn.preparedQuery(SQL_CREATE_USER)
                    .execute(createUserTup.apply(email))
                    .map(rows -> rows.iterator().next().getUUID("id"))
                    .map(uid -> createRoleTup.apply(uid))
                    .compose(
                        roleDetails ->
                            conn.preparedQuery(SQL_CREATE_ROLE).executeBatch(roleDetails))
                    .map(success -> createClientTup.get())
                    .compose(
                        clientDetails ->
                            conn.preparedQuery(SQL_CREATE_CLIENT).execute(clientDetails)))
        .onSuccess(
            row -> {
              resp.put("userId", genUserId.future().result());
              response.complete(resp);
            })
        .onFailure(res -> response.fail("Failed to create fake user" + res.getMessage()));

    return response.future();
  }

  /**
   * Delete list of fake users from DB. Send list of JsonObjects of user details (strictly userId
   * field must be there in each obj)
   *
   * @param pool Postgres PgPool connection to make DB calls
   * @param userList list of JsonObjects of users to delete
   * @return Void future indicating success or failure
   */
  public static Future<Void> deleteFakeUser(PgPool pool, List<JsonObject> userList) {
    Promise<Void> promise = Promise.promise();

    List<UUID> ids =
        userList.stream()
            .map(obj -> UUID.fromString(obj.getString("userId")))
            .collect(Collectors.toList());

    Tuple tuple = Tuple.of(ids.toArray(UUID[]::new));
    pool.withConnection(conn -> conn.preparedQuery(SQL_DELETE_USER_BY_ID).execute(tuple))
        .onSuccess(row -> promise.complete())
        .onFailure(err -> promise.fail("Could not delete users"));

    return promise.future();
  }

  /**
   * Create a mock resource server based on the supplied params.
   *
   * @param pool Postgres PgPool connection to make DB calls
   * @param adminUser JsonObject of the user who is the admin of the server
   * @param resourceServerID UUID of the resourceServer
   * @param itemID URL of the server to be created
   * @return Void future indicating success or failure
   */
  public static Future<Void> createFakeResourceServer(
      PgPool pool, JsonObject adminUser, UUID resourceServerID, String itemID) {
    Promise<Void> response = Promise.promise();
    Tuple tuple =
        Tuple.of(resourceServerID, "testResServer", adminUser.getString("userId"), itemID);
    pool.withConnection(conn -> conn.preparedQuery(SQL_CREATE_RES_SERVER).execute(tuple))
        .onSuccess(succ -> response.complete())
        .onFailure(fail -> response.fail("Db failure: " + fail.toString()));

    return response.future();
  }

  /**
   * Create a mock resource group based on the supplied params.
   *
   * @param pool Postgres PgPool connection to make DB calls
   * @param providerUser JsonObject of the user who is the owner of the server
   * @param resourceServerID UUID of the resourceServer the resource belongs to
   * @param resourceGrpID UUID of the resourceGroup
   * @param itemID cat_id of the resourceGroup to be created
   * @return Void future indicating success or failure
   */
  public static Future<Void> createFakeResourceGroup(
      PgPool pool,
      JsonObject providerUser,
      UUID resourceServerID,
      UUID resourceGrpID,
      String itemID) {
    Promise<Void> response = Promise.promise();
    Tuple tuple =
        Tuple.of(resourceGrpID, itemID, providerUser.getString("userId"), resourceServerID);
    pool.withConnection(conn -> conn.preparedQuery(SQL_CREATE_RES_GRP).execute(tuple))
        .onSuccess(succ -> response.complete())
        .onFailure(fail -> response.fail("Db failure: " + fail.toString()));

    return response.future();
  }

  /**
   * Create a mock resource based on the supplied params.
   *
   * @param pool Postgres PgPool connection to make DB calls
   * @param providerUser JsonObject of the user who is the owner of the server
   * @param resourceId UUID of the resource
   * @param resourceServerId UUID of the resourceServer the resource belongs to
   * @param resourceGrpId UUID of the resourceGroup the resource belongs to
   * @param resource cat_id of the resource to be created
   * @return Void future indicating success or failure
   */
  public static Future<Void> createFakeResource(
      PgPool pool,
      JsonObject providerUser,
      UUID resourceId,
      UUID resourceServerId,
      UUID resourceGrpId,
      String resource) {
    Promise<Void> response = Promise.promise();
    Tuple tuple =
        Tuple.of(
            resourceId,
            resource,
            providerUser.getString("userId"),
            resourceGrpId,
            resourceServerId);
    pool.withConnection(conn -> conn.preparedQuery(SQL_CREATE_RES).execute(tuple))
        .onSuccess(succ -> response.complete())
        .onFailure(fail -> response.fail("Db failure: " + fail.toString()));
    return response.future();
  }

  /**
   * Deletes resources that are added in the beginning or during the tests based on the userID of
   * the owner
   *
   * @param pool Postgres PgPool connection to make DB calls
   * @param users list of JsonObjects of users who own resources
   * @return Void future indicating success or failure
   */
  public static Future<Void> deleteFakeResource(PgPool pool, List<JsonObject> users) {
    Promise<Void> response = Promise.promise();

    List<UUID> ids =
        users.stream()
            .map(obj -> UUID.fromString(obj.getString("userId")))
            .collect(Collectors.toList());
    Tuple tuple = Tuple.of(ids.toArray(UUID[]::new));
    pool.withConnection(conn -> conn.preparedQuery(SQL_DELETE_RESOURCE_BY_OWNER_ID).execute(tuple))
        .onSuccess(succ -> response.complete())
        .onFailure(fail -> response.fail("Db failure: " + fail.toString()));
    return response.future();
  }

  /**
   * Deletes resourceGrps that are added in the beginning or during the tests based on the userID of
   * the owner
   *
   * @param pool Postgres PgPool connection to make DB calls
   * @param users list of JsonObjects of users who own resources
   * @return Void future indicating success or failure
   */
  public static Future<Void> deleteFakeResourceGrp(PgPool pool, List<JsonObject> users) {
    Promise<Void> response = Promise.promise();
    List<UUID> ids =
        users.stream()
            .map(obj -> UUID.fromString(obj.getString("userId")))
            .collect(Collectors.toList());
    Tuple tuple = Tuple.of(ids.toArray(UUID[]::new));
    pool.withConnection(
            conn -> conn.preparedQuery(SQL_DELETE_RESOURCE_GRP_BY_OWNER_ID).execute(tuple))
        .onSuccess(succ -> response.complete())
        .onFailure(fail -> response.fail("Db failure: " + fail.toString()));
    return response.future();
  }

  /**
   * Deletes resourceServers that are added in the beginning or during the tests based on the userID
   * of the owner
   *
   * @param pool Postgres PgPool connection to make DB calls
   * @param users list of JsonObjects of users who own resources
   * @return Void future indicating success or failure
   */
  public static Future<Void> deleteFakeResourceServer(PgPool pool, List<JsonObject> users) {
    Promise<Void> response = Promise.promise();

    List<UUID> ids =
        users.stream()
            .map(obj -> UUID.fromString(obj.getString("userId")))
            .collect(Collectors.toList());
    Tuple tuple = Tuple.of(ids.toArray(UUID[]::new));
    pool.withConnection(
            conn -> conn.preparedQuery(SQL_DELETE_RESOURCE_SERVER_BY_OWNER_ID).execute(tuple))
        .onSuccess(succ -> response.complete())
        .onFailure(fail -> response.fail("Db failure: " + fail.toString()));
    return response.future();
  }

  /**
   * create policies for users
   *
   * @param pool Postgres PgPool connection to make DB calls
   * @param userId UUID of user for whom policy is to be written
   * @param item enum itemTypes of type of item
   * @param ownerId UUID of iser who is writing the policy
   * @param itemId UUID of the id of the item from the resource/resource_group/resource_server table
   * @return Void future indicating success or failure
   */
  public static Future<Void> createFakePolicy(
      PgPool pool, UUID userId, itemTypes item, UUID ownerId, UUID itemId) {
    Promise<Void> response = Promise.promise();
    Tuple tuple = Tuple.of(ownerId, itemId, item, userId);
    pool.withConnection(conn -> conn.preparedQuery(SQL_CREATE_POLICY).execute(tuple))
        .onSuccess(succ -> response.complete())
        .onFailure(fail -> response.fail("Db failure: " + fail.toString()));
    return response.future();
  }

  /**
   * create delegations for users
   *
   * @param pool Postgres PgPool connection to make DB calls
   * @param userId UUID of user for whom delegation is to be written
   * @param ownerId UUID of user who is creating the delegation
   * @param itemId UUID of the id of the resource_server on which delegation is to be made
   * @return Void future indicating success or failure
   */
  public static Future<Void> createDelegation(
      PgPool pool, UUID userId, UUID ownerId, UUID itemId) {
    Promise<Void> response = Promise.promise();
    Tuple tuple = Tuple.of(ownerId,userId,itemId);
    pool.withConnection(conn -> conn.preparedQuery(SQL_CREATE_DELEGATE).execute(tuple))
        .onSuccess(succ -> response.complete())
        .onFailure(fail -> response.fail("Db failure: " + fail.toString()));
    return response.future();
  }
}
