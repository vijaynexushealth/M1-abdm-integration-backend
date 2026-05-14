package com.nhcx.abdm.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
public class AbdmConfig {

    @Value("${abdm.client-id}")           private String clientId;
    @Value("${abdm.client-secret}")        private String clientSecret;
    @Value("${abdm.x-cm-id}")             private String xCmId;

    @Value("${abdm.gateway-base-url}")     private String gatewayBaseUrl;
    @Value("${abdm.abha-base-url}")        private String abhaBaseUrl;
    @Value("${abdm.phr-base-url}")         private String phrBaseUrl;

    @Value("${abdm.endpoint.session}")            private String sessionPath;
    @Value("${abdm.endpoint.bridge-register}")    private String bridgeRegisterPath;
    @Value("${abdm.endpoint.public-cert}")        private String publicCertPath;
    @Value("${abdm.endpoint.enrol-otp}")          private String enrolOtpPath;
    @Value("${abdm.endpoint.enrol-by-aadhaar}")   private String enrolByAadhaarPath;
    @Value("${abdm.endpoint.enrol-verify-abdm}")  private String enrolVerifyAbdmPath;
    @Value("${abdm.endpoint.enrol-suggestion}")   private String enrolSuggestionPath;
    @Value("${abdm.endpoint.enrol-abha-address}") private String enrolAbhaAddressPath;
    @Value("${abdm.endpoint.login-otp}")          private String loginOtpPath;
    @Value("${abdm.endpoint.login-verify}")       private String loginVerifyPath;
    @Value("${abdm.endpoint.login-verify-user}")  private String loginVerifyUserPath;
    @Value("${abdm.endpoint.profile}")            private String profilePath;
    @Value("${abdm.endpoint.profile-qr}")         private String profileQrPath;
    @Value("${abdm.endpoint.profile-card}")       private String profileCardPath;
    @Value("${abdm.endpoint.link-init}")          private String linkInitPath;
    @Value("${abdm.endpoint.link-confirm}")       private String linkConfirmPath;
    @Value("${abdm.endpoint.add-care-context}")   private String addCareContextPath;
    @Value("${abdm.endpoint.notify-care-context}") private String notifyCareContextPath;
    @Value("${abdm.endpoint.consent-init}")       private String consentInitPath;
    @Value("${abdm.endpoint.health-info-request}") private String healthInfoRequestPath;

    // URL builders
    public String sessionUrl()            { return gatewayBaseUrl + sessionPath; }
    public String bridgeRegisterUrl()     { return gatewayBaseUrl + bridgeRegisterPath; }
    public String publicCertUrl()         { return abhaBaseUrl + publicCertPath; }
    public String enrolOtpUrl()           { return abhaBaseUrl + enrolOtpPath; }
    public String enrolByAadhaarUrl()     { return abhaBaseUrl + enrolByAadhaarPath; }
    public String enrolVerifyAbdmUrl()    { return abhaBaseUrl + enrolVerifyAbdmPath; }
    public String enrolSuggestionUrl()    { return abhaBaseUrl + enrolSuggestionPath; }
    public String enrolAbhaAddressUrl()   { return abhaBaseUrl + enrolAbhaAddressPath; }
    public String loginOtpUrl()           { return abhaBaseUrl + loginOtpPath; }
    public String loginVerifyUrl()        { return abhaBaseUrl + loginVerifyPath; }
    public String loginVerifyUserUrl()    { return abhaBaseUrl + loginVerifyUserPath; }
    public String profileUrl()            { return abhaBaseUrl + profilePath; }
    public String profileQrUrl()          { return abhaBaseUrl + profileQrPath; }
    public String profileCardUrl()        { return abhaBaseUrl + profileCardPath; }
    public String linkInitUrl()           { return gatewayBaseUrl + linkInitPath; }
    public String linkConfirmUrl()        { return gatewayBaseUrl + linkConfirmPath; }
    public String addCareContextUrl()     { return gatewayBaseUrl + addCareContextPath; }
    public String notifyCareContextUrl()  { return gatewayBaseUrl + notifyCareContextPath; }
    public String consentInitUrl()        { return gatewayBaseUrl + consentInitPath; }
    public String healthInfoRequestUrl()  { return gatewayBaseUrl + healthInfoRequestPath; }
}
