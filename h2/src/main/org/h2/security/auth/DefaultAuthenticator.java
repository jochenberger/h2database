/*
 * Copyright 2004-2018 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: Alessandro Ventura
 */
package org.h2.security.auth;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.h2.api.CredentialsValidator;
import org.h2.api.UserToRolesMapper;
import org.h2.engine.Database;
import org.h2.engine.Right;
import org.h2.engine.Role;
import org.h2.engine.SysProperties;
import org.h2.engine.User;
import org.h2.engine.UserBuilder;
import org.h2.message.Trace;
import org.h2.security.auth.impl.AssignRealmNameRole;
import org.h2.security.auth.impl.JaasCredentialsValidator;
import org.h2.util.StringUtils;

/**
 * Default authenticator implementation.
 * <p>
 * When client connectionInfo contains property AUTHREALM={realName} credentials
 * (typically user id and password) are validated by by
 * {@link org.h2.api.CredentialsValidator} configured for that realm.
 * </p>
 * <p>
 * When client connectionInfo doesn't contains AUTHREALM property credentials
 * are validated internally on the database
 * </p>
 * <p>
 * Rights assignment can be managed through {@link org.h2.api.UserToRolesMapper}
 * </p>
 * <p>
 * Default configuration has a realm H2 that validate credentials through JAAS
 * api (appName=h2). To customize configuration set h2.authConfigFile system
 * property to refer a valid h2auth.xml config file
 * </p>
 */
public class DefaultAuthenticator implements Authenticator {

    public static final String DEFAULT_REALMNAME = "H2";

    private Map<String, CredentialsValidator> realms = new HashMap<>();

    private List<UserToRolesMapper> userToRolesMappers = new ArrayList<>();

    private boolean allowUserRegistration;

    private boolean persistUsers;

    private boolean createMissingRoles;

    private boolean skipDefaultInitialization;

    private boolean initialized;

    private static DefaultAuthenticator instance;

    protected static final DefaultAuthenticator getInstance() {
        if (instance == null) {
            instance = new DefaultAuthenticator();
        }
        return instance;
    }

    /**
     * Create the Authenticator with default configurations
     */
    public DefaultAuthenticator() {
    }

    /**
     * Create authenticator and optionally skip the default configuration. This
     * option is useful when the authenticator is configured at code level
     *
     * @param skipDefaultInitialization
     *            if true default initialization is skipped
     */
    public DefaultAuthenticator(boolean skipDefaultInitialization) {
        this.skipDefaultInitialization = skipDefaultInitialization;
    }

    /**
     * If set save users externals defined during the authentication.
     *
     * @return
     */
    public boolean isPersistUsers() {
        return persistUsers;
    }

    public void setPersistUsers(boolean persistUsers) {
        this.persistUsers = persistUsers;
    }

    /**
     * If set create external users in the database if not present.
     *
     * @return
     */
    public boolean isAllowUserRegistration() {
        return allowUserRegistration;
    }

    public void setAllowUserRegistration(boolean allowUserRegistration) {
        this.allowUserRegistration = allowUserRegistration;
    }

    /**
     * When set create roles not found in the database. If not set roles not
     * found in the database are silently skipped
     *
     * @return
     */
    public boolean isCreateMissingRoles() {
        return createMissingRoles;
    }

    public void setCreateMissingRoles(boolean createMissingRoles) {
        this.createMissingRoles = createMissingRoles;
    }

    /**
     * Add an authentication realm. Realms are case insensitive
     *
     * @param name
     *            realm name
     * @param credentialsValidator
     *            credentials validator for realm
     */
    public void addRealm(String name, CredentialsValidator credentialsValidator) {
        realms.put(StringUtils.toUpperEnglish(name), credentialsValidator);
    }

    /**
     * UserToRoleMappers assign roles to authenticated users
     *
     * @return current UserToRoleMappers active
     */
    public List<UserToRolesMapper> getUserToRolesMappers() {
        return userToRolesMappers;
    }

    public void setUserToRolesMappers(UserToRolesMapper... userToRolesMappers) {
        List<UserToRolesMapper> userToRolesMappersList = new ArrayList<>();
        for (UserToRolesMapper current : userToRolesMappers) {
            userToRolesMappersList.add(current);
        }
        this.userToRolesMappers = userToRolesMappersList;
    }

    /**
     * Initializes the authenticator.
     *
     * this method is skipped if skipDefaultInitialization is set Order of
     * initialization is
     * <ol>
     * <li>Check h2.authConfigFile system property.</li>
     * <li>Use the default configuration hard coded</li>
     * </ol>
     *
     * @param database
     *            where authenticator is initialized
     * @throws AuthConfigException
     */
    @Override
    public void init(Database database) throws AuthConfigException {
        if (skipDefaultInitialization) {
            return;
        }
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            Trace trace = database.getTrace(Trace.DATABASE);
            URL h2AuthenticatorConfigurationUrl = null;
            try {
                String configFile = SysProperties.AUTH_CONFIG_FILE;
                if (configFile != null) {
                    if (trace.isDebugEnabled()) {
                        trace.debug("DefaultAuthenticator.config: configuration read from system property"
                                + " h2auth.configurationfile={0}", configFile);
                    }
                    h2AuthenticatorConfigurationUrl = new URL(configFile);
                }
                if (h2AuthenticatorConfigurationUrl == null) {
                    if (trace.isDebugEnabled()) {
                        trace.debug("DefaultAuthenticator.config: default configuration");
                    }
                    defaultConfiguration();
                } else {
                    configureFromUrl(h2AuthenticatorConfigurationUrl);
                }
            } catch (Exception e) {
                trace.error(e, "DefaultAuthenticator.config: an error occurred during configuration from {0} ",
                        h2AuthenticatorConfigurationUrl);
                throw new AuthConfigException(
                        "Failed to configure authentication from " + h2AuthenticatorConfigurationUrl, e);
            }
            initialized = true;
        }
    }

    void defaultConfiguration() {
        createMissingRoles = false;
        allowUserRegistration = true;
        realms = new HashMap<>();
        CredentialsValidator jaasCredentialsValidator = new JaasCredentialsValidator();
        jaasCredentialsValidator.configure(new ConfigProperties());
        realms.put(DEFAULT_REALMNAME, jaasCredentialsValidator);
        UserToRolesMapper assignRealmNameRole = new AssignRealmNameRole();
        assignRealmNameRole.configure(new ConfigProperties());
        userToRolesMappers.add(assignRealmNameRole);
    }

    /**
     * Configure the authenticator from a configuration file
     *
     * @param configUrl
     *            URL of configuration file
     * @throws Exception
     */
    public void configureFromUrl(URL configUrl) throws Exception {
        H2AuthConfig config = H2AuthConfigXml.parseFrom(configUrl);
        configureFrom(config);
    }

    void configureFrom(H2AuthConfig config) throws Exception {
        allowUserRegistration = config.isAllowUserRegistration();
        createMissingRoles = config.isCreateMissingRoles();
        Map<String, CredentialsValidator> newRealms = new HashMap<>();
        for (RealmConfig currentRealmConfig : config.getRealms()) {
            String currentRealmName = currentRealmConfig.getName();
            if (currentRealmName == null) {
                throw new Exception("Missing realm name");
            }
            currentRealmName = currentRealmName.toUpperCase();
            CredentialsValidator currentValidator = null;
            try {
                currentValidator = (CredentialsValidator) Class.forName(currentRealmConfig.getValidatorClass())
                        .newInstance();
            } catch (Exception e) {
                throw new Exception("invalid validator class fo realm " + currentRealmName, e);
            }
            currentValidator.configure(new ConfigProperties(currentRealmConfig.getProperties()));
            if (newRealms.put(currentRealmConfig.getName().toUpperCase(), currentValidator) != null) {
                throw new Exception("Duplicate realm " + currentRealmConfig.getName());
            }
        }
        this.realms = newRealms;
        List<UserToRolesMapper> newUserToRolesMapper = new ArrayList<>();
        for (UserToRolesMapperConfig currentUserToRolesMapperConfig : config.getUserToRolesMappers()) {
            UserToRolesMapper currentUserToRolesMapper = null;
            try {
                currentUserToRolesMapper = (UserToRolesMapper) Class
                        .forName(currentUserToRolesMapperConfig.getClassName()).newInstance();
            } catch (Exception e) {
                throw new Exception("Invalid class in UserToRolesMapperConfig", e);
            }
            currentUserToRolesMapper.configure(new ConfigProperties(currentUserToRolesMapperConfig.getProperties()));
            newUserToRolesMapper.add(currentUserToRolesMapper);
        }
        this.userToRolesMappers = newUserToRolesMapper;
    }

    boolean updateRoles(AuthenticationInfo authenticationInfo, User user, Database database)
            throws AuthenticationException {
        boolean updatedDb = false;
        Set<String> roles = new HashSet<>();
        for (UserToRolesMapper currentUserToRolesMapper : userToRolesMappers) {
            Collection<String> currentRoles = currentUserToRolesMapper.mapUserToRoles(authenticationInfo);
            if (currentRoles != null && !currentRoles.isEmpty()) {
                roles.addAll(currentRoles);
            }
        }
        for (String currentRoleName : roles) {
            if (currentRoleName == null || currentRoleName.isEmpty()) {
                continue;
            }
            Role currentRole = database.findRole(currentRoleName);
            if (currentRole == null && isCreateMissingRoles()) {
                synchronized (database.getSystemSession()) {
                    currentRole = new Role(database, database.allocateObjectId(), currentRoleName, false);
                    database.addDatabaseObject(database.getSystemSession(), currentRole);
                    database.getSystemSession().commit(false);
                    updatedDb = true;
                }
            }
            if (currentRole == null) {
                continue;
            }
            if (user.getRightForRole(currentRole) == null) {
                // NON PERSISTENT
                Right currentRight = new Right(database, -1, user, currentRole);
                currentRight.setTemporary(true);
                user.grantRole(currentRole, currentRight);
            }
        }
        return updatedDb;
    }

    @Override
    public final User authenticate(AuthenticationInfo authenticationInfo, Database database)
            throws AuthenticationException {
        String userName = authenticationInfo.getFullyQualifiedName();
        User user = database.findUser(userName);
        if (user == null && !isAllowUserRegistration()) {
            throw new AuthenticationException("User " + userName + " not found in db");
        }
        CredentialsValidator validator = realms.get(authenticationInfo.getRealm());
        if (validator == null) {
            throw new AuthenticationException("realm " + authenticationInfo.getRealm() + " not configured");
        }
        try {
            if (!validator.validateCredentials(authenticationInfo)) {
                return null;
            }
        } catch (Exception e) {
            throw new AuthenticationException(e);
        }
        if (user == null) {
            synchronized (database.getSystemSession()) {
                user = UserBuilder.buildUser(authenticationInfo, database, isPersistUsers());
                database.addDatabaseObject(database.getSystemSession(), user);
                database.getSystemSession().commit(false);
            }
        }
        user.revokeTemporaryRightsOnRoles();
        updateRoles(authenticationInfo, user, database);
        return user;
    }
}