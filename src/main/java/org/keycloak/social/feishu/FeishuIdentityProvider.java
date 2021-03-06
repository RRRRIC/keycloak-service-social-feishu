/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
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
package org.keycloak.social.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.keycloak.OAuth2Constants;
import org.keycloak.OAuthErrorException;
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider;
import org.keycloak.broker.oidc.mappers.AbstractJsonUserAttributeMapper;
import org.keycloak.broker.provider.AuthenticationRequest;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.IdentityBrokerException;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.broker.social.SocialIdentityProvider;
import org.keycloak.common.ClientConnection;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.ErrorPage;
import org.keycloak.services.messages.Messages;

import javax.ws.rs.GET;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * @author Jinxin
 * created at 2022/1/20 10:39
 **/
public class FeishuIdentityProvider extends AbstractOAuth2IdentityProvider<FeishuProviderConfig> implements SocialIdentityProvider<FeishuProviderConfig> {


    public static final String FEISHU_LOGIN_URL = "https://open.feishu.cn/open-apis/authen/v1/index";
    public static final String APP_TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";
    public static final String USER_TOKEN_URL = "https://open.feishu.cn/open-apis/authen/v1/access_token";

    // ????????????????????? '/'
    public static final String USER_DETAIL_URL = "https://open.feishu.cn/open-apis/contact/v3/users/";
    public static final String DEPARTMENT_NAME_URL = "https://open.feishu.cn/open-apis/contact/v3/departments/";

    public static final String FEISHU_APP_ID_PARAM = "app_id";

    private static final Cache<String, String> feishuCache = CacheBuilder.newBuilder()
            .expireAfterWrite(5600, TimeUnit.SECONDS).build();
    private static final String feishuAppAccessToken = "feishuAppAccessToken";

    public static final String FEISHU_PROFILE_MOBILE = "mobile";
    public static final String FEISHU_PROFILE_NAME = "name";
    public static final String FEISHU_PROFILE_UNION_ID = "union_id";
    public static final String FEISHU_PROFILE_EN_NAME = "en_name";
    public static final String FEISHU_PROFILE_EMAIL = "email";
    private static final String FEISHU_PROFILE_NICKNAME = "nickname";

    public static final String FEISHU_PROFILE_COUNTRY = "country";
    public static final String FEISHU_PROFILE_WORK_STATION = "work_station";
    public static final String FEISHU_PROFILE_GENDER = "gender";
    public static final String FEISHU_PROFILE_CITY = "city";
    public static final String FEISHU_PROFILE_EMPLOYEE_NO = "employee_no";
    public static final String FEISHU_PROFILE_JOIN_TIME = "join_time";
    public static final String FEISHU_PROFILE_ENTERPRISE_EMAIL = "enterprise_email";
    public static final String FEISHU_PROFILE_EMPLOYEE_TYPE = "employee_type";
    public static final String FEISHU_PROFILE_IS_TENANT_MANAGER = "is_tenant_manager";
    public static final String FEISHU_PROFILE_JOB_TITLE = "job_title";

    public static final String FEISHU_PROFILE_DEPARTMENT_IDS = "department_ids";

    public static final String FEISHU_PROFILE_STATUS = "status";
    public static final String FEISHU_PROFILE_IS_FROZEN = "is_frozen";
    public static final String FEISHU_PROFILE_IS_ACTIVATED = "is_activated";
    public static final String FEISHU_PROFILE_IS_RESIGNED = "is_resigned";
    public static final String FEISHU_PROFILE_IS_UNJOIN = "is_unjoin";
    public static final String FEISHU_PROFILE_IS_EXITED = "is_exited";


    public FeishuIdentityProvider(KeycloakSession session, FeishuProviderConfig config) {
        super(session, config);
    }


    /**
     * @param realm    ??????????????? realm
     * @param callback Authentication Callback ??????????????????
     * @param event    ???????????????
     * @return ???????????? callback ??????????????? ????????? Spring ?????? Controller ?????? RequestMapping
     * @see org.keycloak.broker.provider.IdentityProvider.AuthenticationCallback
     */
    @Override
    public Object callback(RealmModel realm, AuthenticationCallback callback, EventBuilder event) {
        return new Endpoint(callback, realm, event);
    }

    @Override
    protected boolean supportsExternalExchange() {
        return true;
    }

    /**
     * BrokeredIdentityContext ???????????? Keycloak ?????????????????? <br />
     * BrokeredIdentityContext ?????????????????????????????? <br />
     * ???????????? {@link org.keycloak.broker.provider.BrokeredIdentityContext#setUserAttribute(String, String)} ??????????????????????????????
     * <p>
     * <p>
     * ???????????? (????????????????????????????????????)
     * https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/contact-v3/user/get
     *
     * @param event   ????????????
     * @param profile ????????? JsonNode ??????????????????
     * @return ????????? Keycloak ??????????????????
     * @see org.keycloak.broker.provider.BrokeredIdentityContext
     */
    @Override
    protected BrokeredIdentityContext extractIdentityFromProfile(EventBuilder event, JsonNode profile) {
        logger.info("Received json Profile : " + profile);

        String unionID = getJsonProperty(profile, FEISHU_PROFILE_UNION_ID);
        BrokeredIdentityContext user = new BrokeredIdentityContext(unionID);


        String email = getJsonProperty(profile, FEISHU_PROFILE_ENTERPRISE_EMAIL,
                getJsonProperty(profile, FEISHU_PROFILE_EMAIL, unionID + "@mail.dafault"));

        // ?????????????????????
        user.setUsername(getJsonProperty(profile, FEISHU_PROFILE_NAME, FEISHU_PROFILE_NAME));
        user.setEmail(email);
        user.setFirstName(getJsonProperty(profile, FEISHU_PROFILE_NICKNAME, getJsonProperty(profile, FEISHU_PROFILE_NAME)));
        user.setLastName(getJsonProperty(profile, FEISHU_PROFILE_EN_NAME, getJsonProperty(profile, FEISHU_PROFILE_NAME)));

        // ????????????
        // ??????
        user.setUserAttribute(FEISHU_PROFILE_MOBILE, getJsonProperty(profile, FEISHU_PROFILE_MOBILE));

        // ??????
        user.setUserAttribute(FEISHU_PROFILE_COUNTRY, getJsonProperty(profile, FEISHU_PROFILE_COUNTRY));

        // ?????????
        user.setUserAttribute(FEISHU_PROFILE_WORK_STATION, getJsonProperty(profile, FEISHU_PROFILE_WORK_STATION));

        // ?????? 0 ???????????? 1 ????????? 2 ??????
        user.setUserAttribute(FEISHU_PROFILE_GENDER, getJsonProperty(profile, FEISHU_PROFILE_GENDER));

        // ??????
        user.setUserAttribute(FEISHU_PROFILE_CITY, getJsonProperty(profile, FEISHU_PROFILE_CITY));

        // ????????????
        user.setUserAttribute(FEISHU_PROFILE_EMPLOYEE_NO, getJsonProperty(profile, FEISHU_PROFILE_EMPLOYEE_NO));

        // ????????????
        user.setUserAttribute(FEISHU_PROFILE_EMPLOYEE_TYPE, getJsonProperty(profile, FEISHU_PROFILE_EMPLOYEE_TYPE));

        // ????????????
        Date date = new Date(profile.get(FEISHU_PROFILE_JOIN_TIME).asLong() * 1000);
        String dateStr = formatDate(date);
        user.setUserAttribute(FEISHU_PROFILE_JOIN_TIME, dateStr);

        // ????????????????????????
        user.setUserAttribute(FEISHU_PROFILE_IS_TENANT_MANAGER, getJsonProperty(profile, FEISHU_PROFILE_IS_TENANT_MANAGER));

        // ??????
        user.setUserAttribute(FEISHU_PROFILE_JOB_TITLE, getJsonProperty(profile, FEISHU_PROFILE_JOB_TITLE));

        // ????????????
        StringBuilder departStr = new StringBuilder();
        if (profile.get(FEISHU_PROFILE_DEPARTMENT_IDS).isArray()) {
            for (JsonNode id : profile.get(FEISHU_PROFILE_DEPARTMENT_IDS)) {
                departStr.append(getDepartmentName(id.asText())).append(",");
            }
            if (departStr.length() > 1) {
                departStr.deleteCharAt(departStr.length() - 1);
            }
        }
        user.setUserAttribute(FEISHU_PROFILE_DEPARTMENT_IDS, departStr.toString());


        // ??????
        user.setUserAttribute(FEISHU_PROFILE_IS_FROZEN, getJsonProperty(profile.get(FEISHU_PROFILE_STATUS), FEISHU_PROFILE_IS_FROZEN));
        user.setUserAttribute(FEISHU_PROFILE_IS_ACTIVATED, getJsonProperty(profile.get(FEISHU_PROFILE_STATUS), FEISHU_PROFILE_IS_ACTIVATED));
        user.setUserAttribute(FEISHU_PROFILE_IS_RESIGNED, getJsonProperty(profile.get(FEISHU_PROFILE_STATUS), FEISHU_PROFILE_IS_RESIGNED));
        user.setUserAttribute(FEISHU_PROFILE_IS_UNJOIN, getJsonProperty(profile.get(FEISHU_PROFILE_STATUS), FEISHU_PROFILE_IS_UNJOIN));
        user.setUserAttribute(FEISHU_PROFILE_IS_EXITED, getJsonProperty(profile.get(FEISHU_PROFILE_STATUS), FEISHU_PROFILE_IS_EXITED));

        user.setIdpConfig(getConfig());
        user.setIdp(this);
        AbstractJsonUserAttributeMapper.storeUserProfileForMapper(user, profile, getConfig().getAlias());
        return user;
    }


    /**
     * ??????????????? + app_access_token ???????????????????????????
     * ???????????? : https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/authen-v1/authen/access_token
     *
     * @param code SNS ?????????
     * @return ????????? Keycloak ????????????????????????
     */
    public BrokeredIdentityContext getFederatedIdentity(String code) {
        try {
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put(OAUTH2_PARAMETER_GRANT_TYPE, OAUTH2_GRANT_TYPE_AUTHORIZATION_CODE);
            requestBody.put(OAUTH2_PARAMETER_CODE, code);
            String appToken = getAppAccessToken();

            JsonNode responseJson = SimpleHttp.doPost(USER_TOKEN_URL, session)
                    .header("Authorization", "Bearer " + appToken)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .json(requestBody)
                    .asJson();
            if (responseJson.get("code").asInt(-1) != 0) {
                logger.warn("Can't get user info , response :" + responseJson);
                throw new Exception("Can't get user info");
            }

            String userAccessToken = responseJson.get("data").get(OAUTH2_PARAMETER_ACCESS_TOKEN).asText();
            String userId = responseJson.get("data").get("user_id").asText();
            JsonNode userDetail = getUserDetailByAppAccessTokenAndUserId(userAccessToken, userId);

            return extractIdentityFromProfile(null, userDetail);

        } catch (Exception e) {
            throw new IdentityBrokerException("Could not obtain user profile from feishu." + e.getMessage(), e);
        }
    }

    /**
     * ???????????? : https://open.feishu.cn/document/ukTMukTMukTM/ukDNz4SO0MjL5QzM/auth-v3/auth/tenant_access_token_internal
     *
     * @return ??????????????? AppID ????????? access token
     */
    private String getAppAccessToken() throws Exception {
        String appId = getConfig().getClientId();
        if (!isBlank(feishuCache.getIfPresent(feishuAppAccessToken + appId))) {
            return feishuCache.getIfPresent(feishuAppAccessToken + appId);
        }
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("app_id", appId);
        requestBody.put("app_secret", getConfig().getClientSecret());
        JsonNode responseJson = SimpleHttp.doPost(APP_TOKEN_URL, session)
                .header("Content-Type", "application/json; charset=utf-8")
                .json(requestBody).asJson();
        if (responseJson.get("code").asInt(-1) != 0) {
            logger.warn("Can't get app access token , response :" + responseJson);
            throw new Exception("Can't get app access token");
        }
        String token = getJsonProperty(responseJson, "tenant_access_token");
        feishuCache.put(feishuAppAccessToken + appId, token);
        return token;
    }

    /**
     * ??????????????????????????????
     * Token ????????????????????? user_access_token
     * ?????? ID ????????????????????? open_id, union_id
     * ?????????????????????????????????
     * <p>
     * ???????????? : https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/contact-v3/user/get
     *
     * @param userAccessToken ????????? accessToken
     * @param userId          ?????? ID
     * @return ??????????????????????????? JsonNode
     */
    private JsonNode getUserDetailByAppAccessTokenAndUserId(String userAccessToken, String userId) throws Exception {
        String userDetailWithUserIdUrl = USER_DETAIL_URL + userId;
        JsonNode responseJson = SimpleHttp.doGet(userDetailWithUserIdUrl, session)
                .header("Authorization", "Bearer " + userAccessToken)
                .param("user_id_type", "user_id")
                .asJson();
        if (responseJson.get("code").asInt(-1) != 0) {
            logger.warn("Can't get user detail info , response :" + responseJson);
            logger.info("access token :" + userAccessToken + " userId : " + userId);
            throw new Exception("Can't get user detail info");
        }
        return responseJson.get("data").get("user");
    }


    /**
     * ???????????? : https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/contact-v3/department/get
     *
     * @param departmentId ?????? ID
     * @return ?????? ID ???????????????
     */
    private String getDepartmentName(String departmentId) {
        try {
            String userDetailWithUserIdUrl = DEPARTMENT_NAME_URL + departmentId;
            JsonNode responseJson = SimpleHttp.doGet(userDetailWithUserIdUrl, session)
                    .header("Authorization", "Bearer " + getAppAccessToken())
                    .param("department_id_type", "department_id")
                    .asJson();
            if (responseJson.get("code").asInt(-1) != 0) {
                logger.warn("Can't get department name , response :" + responseJson);
                return "";
            }
            return responseJson.get("data").get("department").get("name").asText();
        } catch (Exception ignore) {
            return "";
        }
    }

    /**
     * ???????????????????????????????????????????????????
     * ?????????????????? Response.seeOther ??????
     *
     * @param request ????????????
     * @return 302 ????????? response
     * @see javax.ws.rs.core.Response#seeOther(URI)
     */
    @Override
    public Response performLogin(AuthenticationRequest request) {
        try {
            URI authorizationUrl = createAuthorizationUrl(request).build();
            logger.info("auth url " + authorizationUrl.toString());
            return Response.seeOther(authorizationUrl).build();
        } catch (Exception e) {
            e.printStackTrace(System.out);
            throw new IdentityBrokerException("Could not create authentication request. ", e);
        }
    }


    /**
     * ????????????????????????????????????
     * ???????????????https://open.feishu.cn/document/ukTMukTMukTM/ukzN4UjL5cDO14SO3gTN
     *
     * @param request ????????????
     * @return ??????????????????
     */
    @Override
    protected UriBuilder createAuthorizationUrl(AuthenticationRequest request) {
        return UriBuilder.fromUri(FEISHU_LOGIN_URL)
                .queryParam(OAUTH2_PARAMETER_STATE, request.getState().getEncoded())
                .queryParam(FEISHU_APP_ID_PARAM, getConfig().getClientId())
                .queryParam(OAUTH2_PARAMETER_REDIRECT_URI, request.getRedirectUri());
    }


    /**
     * ???????????? OAuth2 ????????? CallBack Endpoint
     * ?????????????????????
     */
    protected class Endpoint {
        protected AuthenticationCallback callback;
        protected RealmModel realm;
        protected EventBuilder event;

        @Context
        protected KeycloakSession session;

        @Context
        protected ClientConnection clientConnection;

        @Context
        protected HttpHeaders headers;

        public Endpoint(AuthenticationCallback callback, RealmModel realm, EventBuilder event) {
            this.callback = callback;
            this.realm = realm;
            this.event = event;
        }

        /**
         * Endpoint ????????? callback ????????? <br />
         * ????????????????????????????????????????????????????????? IDP ?????????????????????????????? Response ??? IdpContext ??? <br />
         * ??????????????????????????????????????? Attribute ??? <br />
         *
         * @param state state?????????
         * @param code  ???????????????
         * @param error ?????????????????? Error
         * @return ???????????? token ??????????????????
         */
        @GET
        public Response authResponse(@QueryParam(OAUTH2_PARAMETER_STATE) String state,
                                     @QueryParam(OAUTH2_PARAMETER_CODE) String code,
                                     @QueryParam(OAuth2Constants.ERROR) String error) {
            if (error != null) {
                logger.error(error + " for broker login " + getConfig().getProviderId());
                if (error.equals(ACCESS_DENIED)) {
                    return callback.cancelled(state);
                } else if (error.equals(OAuthErrorException.LOGIN_REQUIRED) || error.equals(OAuthErrorException.INTERACTION_REQUIRED)) {
                    return callback.error(state, error);
                } else {
                    return callback.error(state, Messages.IDENTITY_PROVIDER_UNEXPECTED_ERROR);
                }
            }
            try {

                BrokeredIdentityContext federatedIdentity;

                federatedIdentity = getFederatedIdentity(code);
                // ????????? state ????????? code ??? ????????????????????? ??????????????????
                // ??? code ?????????????????? code??? ?????? state
                federatedIdentity.setCode(state);
                federatedIdentity.setIdpConfig(getConfig());
                federatedIdentity.setIdp(FeishuIdentityProvider.this);

                event.user(federatedIdentity.getBrokerUserId());
                event.client(getConfig().getClientId());
                return callback.authenticated(federatedIdentity);

            } catch (WebApplicationException e) {
                logger.error(e.getMessage(), e);
                return e.getResponse();
            } catch (Exception e) {
                logger.error("Failed to make identity provider oauth callback", e);
            }
            event.event(EventType.LOGIN);
            event.error(Errors.IDENTITY_PROVIDER_LOGIN_FAILURE);
            return ErrorPage.error(session, null, Response.Status.BAD_GATEWAY, Messages.IDENTITY_PROVIDER_UNEXPECTED_ERROR);
        }

    }

    @Override
    public void updateBrokeredUser(KeycloakSession session, RealmModel realm, UserModel user, BrokeredIdentityContext context) {
        user.setUsername(context.getUsername());
        user.setEmail(context.getEmail());
        user.setFirstName(context.getFirstName());
        user.setLastName(context.getLastName());

        // ??????
        user.setSingleAttribute(FEISHU_PROFILE_MOBILE, context.getUserAttribute(FEISHU_PROFILE_MOBILE));
        user.setSingleAttribute(FEISHU_PROFILE_COUNTRY, context.getUserAttribute(FEISHU_PROFILE_COUNTRY));
        user.setSingleAttribute(FEISHU_PROFILE_WORK_STATION, context.getUserAttribute(FEISHU_PROFILE_WORK_STATION));
        user.setSingleAttribute(FEISHU_PROFILE_GENDER, context.getUserAttribute(FEISHU_PROFILE_GENDER));
        user.setSingleAttribute(FEISHU_PROFILE_CITY, context.getUserAttribute(FEISHU_PROFILE_CITY));
        user.setSingleAttribute(FEISHU_PROFILE_EMPLOYEE_NO, context.getUserAttribute(FEISHU_PROFILE_EMPLOYEE_NO));
        user.setSingleAttribute(FEISHU_PROFILE_EMPLOYEE_TYPE, context.getUserAttribute(FEISHU_PROFILE_EMPLOYEE_TYPE));
        user.setSingleAttribute(FEISHU_PROFILE_JOIN_TIME, context.getUserAttribute(FEISHU_PROFILE_JOIN_TIME));
        user.setSingleAttribute(FEISHU_PROFILE_IS_TENANT_MANAGER, context.getUserAttribute(FEISHU_PROFILE_IS_TENANT_MANAGER));
        user.setSingleAttribute(FEISHU_PROFILE_JOB_TITLE, context.getUserAttribute(FEISHU_PROFILE_JOB_TITLE));
        user.setSingleAttribute(FEISHU_PROFILE_DEPARTMENT_IDS, context.getUserAttribute(FEISHU_PROFILE_DEPARTMENT_IDS));
        user.setSingleAttribute(FEISHU_PROFILE_IS_FROZEN, context.getUserAttribute(FEISHU_PROFILE_IS_FROZEN));
        user.setSingleAttribute(FEISHU_PROFILE_IS_ACTIVATED, context.getUserAttribute(FEISHU_PROFILE_IS_ACTIVATED));
        user.setSingleAttribute(FEISHU_PROFILE_IS_RESIGNED, context.getUserAttribute(FEISHU_PROFILE_IS_RESIGNED));
        user.setSingleAttribute(FEISHU_PROFILE_IS_UNJOIN, context.getUserAttribute(FEISHU_PROFILE_IS_UNJOIN));
        user.setSingleAttribute(FEISHU_PROFILE_IS_EXITED, context.getUserAttribute(FEISHU_PROFILE_IS_EXITED));

    }


    /**
     * ?????????????????????????????? scope ??????
     *
     * @return default scope
     */
    @Override
    protected String getDefaultScopes() {
        return "default scope";
    }


    @Override
    public String getJsonProperty(JsonNode jsonNode, String name) {
        if (jsonNode != null && jsonNode.has(name) && !jsonNode.get(name).isNull()) {
            String s = jsonNode.get(name).asText();
            if (s != null && !s.isEmpty())
                return s;
            else
                return "";
        }
        return "";
    }


    public String getJsonListProperty(JsonNode jsonNode, String fieldName) {
        if (!jsonNode.isArray()) {
            return getJsonProperty(jsonNode, fieldName);
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode node : jsonNode) {
            sb.append(getJsonProperty(node, fieldName)).append(",");
        }
        if (sb.length() >= 1) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    public String getJsonProperty(JsonNode jsonNode, String name, String defaultValue) {
        if (jsonNode != null && jsonNode.has(name) && !jsonNode.get(name).isNull()) {
            String s = jsonNode.get(name).asText();
            if (s != null && !s.isEmpty())
                return s;
            else
                return defaultValue;
        }

        return defaultValue;
    }

    public static boolean isBlank(String s) {
        if (s == null || s.length() < 1) {
            return true;
        }
        for (char c : s.toCharArray()) {
            if (c != ' ') {
                return false;
            }
        }
        return true;
    }

    private final static SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public static String formatDate(Date date) {
        return format.format(date);
    }
}
