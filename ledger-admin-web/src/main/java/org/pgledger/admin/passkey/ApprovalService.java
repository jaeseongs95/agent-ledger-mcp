package org.pgledger.admin.passkey;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.pgledger.admin.passkey.ApprovalIntents.Binding;
import org.pgledger.application.admin.PasskeyService;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.model.OwnerPasskey;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The approval of an owner write by a passkey, on the WebAuthn support of Spring Security: the server makes the request options
 * itself, with the challenge derived from the change ({@link ApprovalIntents}) and user verification required, and
 * {@link WebAuthnRelyingPartyOperations#authenticate} checks the assertion (client data type, challenge, origin, relying party id
 * hash, user presence and verification, signature) against the live passkey it names. The same approval is checked again on the
 * server, from what the server remembers, when the write arrives: a challenge that was not asked for, was used, has expired or is
 * for another change or another session is refused before any signature is looked at.
 */
@Component
public class ApprovalService {

    /** What the page needs for {@code navigator.credentials.get}: every byte string base64url. */
    public record Options(String challenge, String rpId, List<String> allowCredentials, String userVerification,
            long timeout) {
    }

    /** What the page sends back with the write: the four parts of an assertion, base64url. */
    public record Assertion(String credentialId, String authenticatorData, String clientDataJson, String signature) {
    }

    /** What this check reads of the client data: the challenge. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Client(String challenge) {
    }

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final PasskeyService passkeys;
    private final PasskeyRelyingParty party;
    private final ApprovalIntents intents;
    private final WebAuthnRelyingPartyOperations operations;
    private final JsonMapper mapper;

    public ApprovalService(PasskeyService passkeys, PasskeyRelyingParty party, ApprovalIntents intents,
            WebAuthnRelyingPartyOperations operations, JsonMapper mapper) {
        this.passkeys = passkeys;
        this.party = party;
        this.intents = intents;
        this.operations = operations;
        this.mapper = mapper;
    }

    /**
     * The options of an approval of this write in this session.
     *
     * @throws AdminException PASSKEY_NONE_REGISTERED when no passkey is live
     */
    public Options options(Binding binding) {
        List<String> ids = passkeys.live().stream().map(OwnerPasskey::credentialId).toList();
        if (ids.isEmpty()) {
            throw new AdminException(AdminCode.PASSKEY_NONE_REGISTERED, List.of());
        }
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(intents.issue(binding));
        return new Options(challenge, party.rpId(), ids, "required", TIMEOUT.toMillis());
    }

    /**
     * Checks the approval that came with a write.
     *
     * @param assertionJson the {@link Assertion} as JSON
     * @param binding       the write that came, as the server sees it
     * @throws AdminException PASSKEY_APPROVAL_INVALID, PASSKEY_ASSERTION_INVALID, PASSKEY_HOST_MISMATCH or
     *         PASSKEY_NONE_REGISTERED
     */
    public void verify(String assertionJson, Binding binding) {
        Assertion assertion;
        String challenge = null;
        try {
            assertion = mapper.readValue(assertionJson, Assertion.class);
            if (assertion != null && assertion.clientDataJson() != null) {
                Client client = mapper.readValue(Base64.getUrlDecoder().decode(assertion.clientDataJson()), Client.class);
                challenge = client == null ? null : client.challenge();
            }
        } catch (JacksonException | IllegalArgumentException malformed) {
            throw new AdminException(AdminCode.PASSKEY_APPROVAL_INVALID, List.of("malformed"));
        }
        if (challenge == null || assertion.credentialId() == null || assertion.authenticatorData() == null
                || assertion.signature() == null) {
            throw new AdminException(AdminCode.PASSKEY_APPROVAL_INVALID, List.of("malformed"));
        }
        intents.spend(challenge, binding);
        List<OwnerPasskey> live = passkeys.live();
        try {
            PublicKeyCredentialRequestOptions options = PublicKeyCredentialRequestOptions.builder()
                    .challenge(Bytes.fromBase64(challenge)).rpId(party.rpId()).timeout(TIMEOUT)
                    .allowCredentials(live.stream().map(passkey -> PublicKeyCredentialDescriptor.builder()
                            .type(PublicKeyCredentialType.PUBLIC_KEY).id(Bytes.fromBase64(passkey.credentialId()))
                            .build()).toList())
                    .userVerification(UserVerificationRequirement.REQUIRED).build();
            AuthenticatorAssertionResponse response = AuthenticatorAssertionResponse.builder()
                    .authenticatorData(Bytes.fromBase64(assertion.authenticatorData()))
                    .clientDataJSON(Bytes.fromBase64(assertion.clientDataJson()))
                    .signature(Bytes.fromBase64(assertion.signature())).build();
            // The setters return the raw builder type, so they are not chained (-Werror unchecked).
            PublicKeyCredential.PublicKeyCredentialBuilder<AuthenticatorAssertionResponse> builder = PublicKeyCredential
                    .builder();
            builder.id(assertion.credentialId());
            builder.type(PublicKeyCredentialType.PUBLIC_KEY);
            builder.rawId(Bytes.fromBase64(assertion.credentialId()));
            builder.response(response);
            PublicKeyCredential<AuthenticatorAssertionResponse> credential = builder.build();
            operations.authenticate(new RelyingPartyAuthenticationRequest(options, credential));
        } catch (IllegalArgumentException | com.webauthn4j.util.exception.WebAuthnException failure) {
            throw AssertionFailures.translate(failure, party.rpId(), !live.isEmpty());
        }
    }
}
