package eu.bittrade.libs.steemj.base.models;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.lang.annotation.AnnotationFormatError;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.security.InvalidParameterException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.ECKey.ECDSASignature;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;

import eu.bittrade.libs.steemj.annotations.SignatureRequired;
import eu.bittrade.libs.steemj.base.models.operations.Operation;
import eu.bittrade.libs.steemj.configuration.PrivateKeyStorage;
import eu.bittrade.libs.steemj.configuration.SteemJConfig;
import eu.bittrade.libs.steemj.exceptions.SteemFatalErrorException;
import eu.bittrade.libs.steemj.exceptions.SteemInvalidTransactionException;
import eu.bittrade.libs.steemj.interfaces.ByteTransformable;
import eu.bittrade.libs.steemj.util.SteemJUtils;

/**
 * This class represents a Steem "signed_transaction" object.
 * 
 * @author <a href="http://Steemit.com/@dez1337">dez1337</a>
 */
public class SignedTransaction extends Transaction implements ByteTransformable, Serializable {
    private static final long serialVersionUID = 4821422578657270330L;
    private static final Logger LOGGER = LoggerFactory.getLogger(SignedTransaction.class);

    protected transient List<String> signatures;

    /**
     * Create a new Transaction.
     */
    public SignedTransaction() {
        // Set default values to avoid null pointer exceptions.
        this.signatures = new ArrayList<>();
    }

    /**
     * Get the signatures for this transaction. This method is only used for
     * JSON deserialization and for testing purposes.
     * 
     * @return An array of currently appended signatures.
     */
    @JsonProperty("signatures")
    protected List<String> getSignatures() {
        return this.signatures;
    }

    /**
     * Verify that the signature is canonical.
     * 
     * Original implementation can be found <a href=
     * "https://github.com/kenCode-de/graphenej/blob/master/graphenej/src/main/java/de/bitsharesmunich/graphenej/Transaction.java"
     * >here.</a>
     * 
     * @param signature
     *            A single signature in its byte representation.
     * @return True if the signature is canonical or false if not.
     */
    private boolean isCanonical(byte[] signature) {
        return ((signature[0] & 0x80) != 0) || (signature[0] == 0) || ((signature[1] & 0x80) != 0)
                || ((signature[32] & 0x80) != 0) || (signature[32] == 0) || ((signature[33] & 0x80) != 0);
    }

    /**
     *
     * Like {@link #sign(String) sign(String)}, but uses the default Steem chain
     * id.
     *
     * @throws SteemInvalidTransactionException
     *             If the transaction can not be signed.
     */
    public void sign() throws SteemInvalidTransactionException {
        sign(SteemJConfig.getInstance().getChainId());
    }

    /**
     * Use this method if you want to specify a different chainId than the
     * default one for STEEM. Otherwise use the {@link #sign() sign()} method.
     * 
     * @param chainId
     *            The chain id that should be used during signing.
     * @throws SteemInvalidTransactionException
     *             If the transaction can not be signed.
     */
    public void sign(String chainId) throws SteemInvalidTransactionException {
        // Before we start signing the transaction we check if all required
        // fields are present, which private keys are required and if those keys
        // are provided.
        if (this.getExpirationDate() == null || this.getExpirationDate().getDateTimeAsTimestamp() == 0) {
            // The expiration date is not set by the user so we do it on our own
            // by adding the maximal allowed offset to the current time.
            this.setExpirationDate(new TimePointSec(
                    System.currentTimeMillis() + SteemJConfig.getInstance().getMaximumExpirationDateOffset() - 60000L));
            LOGGER.debug("No expiration date has been provided so the latest possible time is used.");
        } else if (this.getExpirationDate()
                .getDateTimeAsTimestamp() > (new Timestamp(System.currentTimeMillis())).getTime()
                        + SteemJConfig.getInstance().getMaximumExpirationDateOffset()) {
            LOGGER.warn("The configured expiration date for this transaction is to far "
                    + "in the future and may not be accepted by the Steem node.");
        } else if (this.operations == null || this.operations.isEmpty()) {
            throw new SteemInvalidTransactionException("At least one operation is required to sign the transaction.");
        } else if (this.refBlockNum == 0) {
            throw new SteemInvalidTransactionException("The refBlockNum field needs to be set.");
        } else if (this.refBlockPrefix == 0) {
            throw new SteemInvalidTransactionException("The refBlockPrefix field needs to be set.");
        }

        List<ECKey> requiredPrivateKeys = getRequiredSignatures();

        for (ECKey requiredPrivateKey : requiredPrivateKeys) {
            boolean isCanonical = false;
            byte[] signedTransaction = null;

            Sha256Hash messageAsHash;
            while (!isCanonical) {
                try {
                    messageAsHash = Sha256Hash.wrap(Sha256Hash.hash(this.toByteArray(chainId)));
                } catch (SteemInvalidTransactionException e) {
                    throw new SteemInvalidTransactionException(
                            "The required encoding is not supported by your platform.", e);
                }
                ECDSASignature signature = requiredPrivateKey.sign(messageAsHash);

                /*
                 * Identify the correct key type (posting, active, owner, memo)
                 * by iterating through the types and comparing the elliptic
                 * curves.
                 */
                Integer recId = null;
                for (int i = 0; i < 4; i++) {
                    ECKey publicKey = ECKey.recoverFromSignature(i, signature, messageAsHash,
                            requiredPrivateKey.isCompressed());
                    if (publicKey != null && publicKey.getPubKeyPoint().equals(requiredPrivateKey.getPubKeyPoint())) {
                        recId = i;
                        break;
                    }
                }

                if (recId == null) {
                    throw new SteemFatalErrorException(
                            "Could not construct a recoverable key. This should never happen.");
                }

                int headerByte = recId + 27 + (requiredPrivateKey.isCompressed() ? 4 : 0);
                signedTransaction = new byte[65];
                signedTransaction[0] = (byte) headerByte;
                System.arraycopy(Utils.bigIntegerToBytes(signature.r, 32), 0, signedTransaction, 1, 32);
                System.arraycopy(Utils.bigIntegerToBytes(signature.s, 32), 0, signedTransaction, 33, 32);

                if (isCanonical(signedTransaction)) {
                    this.getExpirationDate().setDateTime(this.getExpirationDate().getDateTimeAsTimestamp() + 1);
                } else {
                    isCanonical = true;
                }
            }

            this.signatures.add(Utils.HEX.encode(signedTransaction));
        }
    }

    // public Map<AccountName, PrivateKeyType> getRequiredAuthorities() {

    // }

    // public void verifyAuthority() {

    // }

    /**
     * This method collects the required signatures of all operations stored in
     * this transaction. The returned list is already a minimized version to
     * avoid an "irrelevant signature included Unnecessary signature(s)
     * detected" error.
     * 
     * @return A list of the required private keys.
     * @throws SteemInvalidTransactionException
     *             If required private keys are missing in the
     *             {@link PrivateKeyStorage PrivateKeyStorage}.
     */
    protected List<ECKey> getRequiredSignatures() throws SteemInvalidTransactionException {
        List<ECKey> requiredSignatures = new ArrayList<>();

        // Iterate over all Operations.
        for (Operation operation : this.getOperations()) {
            List<Field> fields = FieldUtils.getFieldsListWithAnnotation(operation.getClass(), SignatureRequired.class);
            for (Field field : fields) {
                SignatureRequired signatureRequired = field.getDeclaredAnnotation(SignatureRequired.class);

                try {
                    // Disable access check.
                    field.setAccessible(true);
                    if (field.getType().equals(AccountName.class)) {
                        ECKey privateKey = SteemJConfig.getInstance().getPrivateKeyStorage()
                                .getKeyForAccount(signatureRequired.type(), (AccountName) field.get(operation));
                        if (!requiredSignatures.contains(privateKey)) {
                            requiredSignatures.add(privateKey);
                        }
                    } else if (field.getType().equals(List.class)
                            && ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0]
                                    .equals(AccountName.class)) {
                        List<AccountName> accountNameList = (List<AccountName>) field.get(operation);
                        if (!accountNameList.isEmpty()) {
                            for (AccountName accountName : accountNameList) {
                                ECKey privateKey = SteemJConfig.getInstance().getPrivateKeyStorage()
                                        .getKeyForAccount(signatureRequired.type(), accountName);
                                if (!requiredSignatures.contains(privateKey)) {
                                    requiredSignatures.add(privateKey);
                                }
                            }
                        }
                    } else {
                        throw new AnnotationFormatError("Wrong annotation usage. Please report this issue at GitHub.");
                    }
                } catch (IllegalAccessException e) {
                    throw new AnnotationFormatError("Not allowed to access the field.");
                } catch (InvalidParameterException ipe) {
                    throw new SteemInvalidTransactionException("Could not find all required keys to sign the '"
                            + operation.getClass().getSimpleName() + "'.", ipe);
                }

            }
        }

        return requiredSignatures;
    }

    /**
     * Like {@link #toByteArray(String) toByteArray(String)}, but uses the
     * default Steem chain id.
     * 
     * @throws SteemInvalidTransactionException
     *             If the transaction can not be signed.
     */
    @Override
    public byte[] toByteArray() throws SteemInvalidTransactionException {
        return toByteArray(SteemJConfig.getInstance().getChainId());
    }

    /**
     * This method creates a byte array based on a transaction object under the
     * use of a guide written by <a href="https://Steemit.com/Steem/@xeroc/">
     * Xeroc</a>. This method should only be used internally.
     * 
     * If a chainId is provided it will be added in front of the byte array.
     * 
     * @return The serialized transaction object.
     * @param chainId
     *            The HEX representation of the chain Id you want to use for
     *            this transaction.
     * @throws SteemInvalidTransactionException
     *             If the transaction can not be signed.
     */
    protected byte[] toByteArray(String chainId) throws SteemInvalidTransactionException {
        getRequiredSignatures();
        try (ByteArrayOutputStream serializedTransaction = new ByteArrayOutputStream()) {
            if (chainId != null && !chainId.isEmpty()) {
                serializedTransaction.write(Utils.HEX.decode(chainId));
            }
            serializedTransaction.write(SteemJUtils.transformShortToByteArray(this.getRefBlockNum()));
            serializedTransaction.write(SteemJUtils.transformIntToByteArray((int) this.getRefBlockPrefix()));
            serializedTransaction.write(this.getExpirationDate().toByteArray());
            serializedTransaction.write(SteemJUtils.transformLongToVarIntByteArray(this.getOperations().size()));

            for (Operation operation : this.getOperations()) {
                serializedTransaction.write(operation.toByteArray());
            }

            for (FutureExtensions futureExtensions : this.getExtensions()) {
                serializedTransaction.write(futureExtensions.toByteArray());
            }

            return serializedTransaction.toByteArray();
        } catch (IOException e) {
            throw new SteemInvalidTransactionException(
                    "A problem occured while transforming the transaction into a byte array.", e);
        }
    }

    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this);
    }
}
