package cc.coopersoft.keycloak.phone.providers.spi.impl;

import cc.coopersoft.common.OptionalStringUtils;
import cc.coopersoft.keycloak.phone.providers.spi.PhoneProvider;
import cc.coopersoft.keycloak.phone.providers.spi.PhoneVerificationCodeProvider;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.exception.MessageSendException;
import cc.coopersoft.keycloak.phone.providers.representations.TokenCodeRepresentation;
import cc.coopersoft.keycloak.phone.providers.spi.MessageSenderService;
import cc.coopersoft.keycloak.phone.Utils;
import org.apache.commons.lang.StringUtils;
import org.jboss.logging.Logger;
import org.keycloak.Config.Scope;
import org.keycloak.models.KeycloakSession;
import com.google.i18n.phonenumbers.NumberParseException;

import javax.ws.rs.ForbiddenException;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.ServiceUnavailableException;
import java.time.Instant;
import java.util.Optional;

public class DefaultPhoneProvider implements PhoneProvider {

    private static final Logger logger = Logger.getLogger(DefaultPhoneProvider.class);
    private final KeycloakSession session;
    private final String service;
    private final int tokenExpiresIn;
    private final int hourMaximum;

    private final Scope config;

    DefaultPhoneProvider(KeycloakSession session, Scope config) {
        this.session = session;
        this.config = config;


        this.service = session.listProviderIds(MessageSenderService.class)
                .stream().filter(s -> s.equals(config.get("service")))
                .findFirst().orElse(
                        session.listProviderIds(MessageSenderService.class)
                                .stream().findFirst().orElse(null)
                );

        if (StringUtils.isBlank(this.service)){
            logger.error("Message sender service provider not found!");
        }

        if (StringUtils.isBlank(config.get("service")))
            logger.warn("No message sender service provider specified! Default provider'" +
                this.service + "' will be used. You can use keycloak start param '--spi-phone-default-service' to specify a different one. ");

        this.tokenExpiresIn = config.getInt("tokenExpiresIn", 60);
        this.hourMaximum = config.getInt("hourMaximum",3);
    }



    @Override
    public void close() {
    }


    private PhoneVerificationCodeProvider getTokenCodeService() {
        return session.getProvider(PhoneVerificationCodeProvider.class);
    }

    @Override
    public boolean isDuplicatePhoneAllowed(String realm) {
        Boolean result = config.getBoolean(realm + "-duplicate-phone",null);
        if (result == null) {
            result = config.getBoolean("duplicate-phone",false);
        }
        return result;
    }

    @Override
    public Optional<String> phoneNumberRegx(String realm) {
        return OptionalStringUtils.ofBlank(OptionalStringUtils.ofBlank(config.get(realm + "-number-regx"))
            .orElse(config.get("number-regx")));
    }

    @Override
    public String canonicalizePhoneNumber(String phoneNumber) {
        if (config.getBoolean("canonicalize-phone-numbers",false)) {
            try {
                return Utils.canonicalizePhoneNumber(session, phoneNumber, defaultPhoneRegion());
            } catch (NumberParseException e) {
                throw new BadRequestException("Unable to parse phone number. " + e.toString());
            }
        } else {
            return phoneNumber;
        }
    }

    @Override
    public Optional<String> defaultPhoneRegion() {
        return OptionalStringUtils.ofEmpty(config.get("phone-region"));
    }

    @Override
    public int sendTokenCode(String phoneNumber,TokenCodeType type, String kind){
        logger.info("send code to:" + phoneNumber );

        if (getTokenCodeService().isAbusing(phoneNumber, type,hourMaximum)) {
            throw new ForbiddenException("You requested the maximum number of messages the last hour");
        }

        TokenCodeRepresentation ongoing = getTokenCodeService().ongoingProcess(phoneNumber, type);
        if (ongoing != null) {
            logger.info(String.format("No need of sending a new %s code for %s",type.getLabel(), phoneNumber));
            return (int) (ongoing.getExpiresAt().getTime() - Instant.now().toEpochMilli()) / 1000;
        }

        TokenCodeRepresentation token = TokenCodeRepresentation.forPhoneNumber(phoneNumber);

        try {
            session.getProvider(MessageSenderService.class, service).sendSmsMessage(type,phoneNumber,token.getCode(),tokenExpiresIn,kind);
            getTokenCodeService().persistCode(token, type, tokenExpiresIn);

            logger.info(String.format("Sent %s code to %s over %s",type.getLabel(), phoneNumber, service));

        } catch (MessageSendException e) {

            logger.error(String.format("Message sending to %s failed with %s: %s",
                    phoneNumber, e.getErrorCode(), e.getErrorMessage()));
            throw new ServiceUnavailableException("Internal server error");
        }

        return tokenExpiresIn;
    }

}
