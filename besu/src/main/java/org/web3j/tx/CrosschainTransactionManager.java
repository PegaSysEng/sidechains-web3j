/*
 * Copyright 2019 Web3 Labs LTD.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.web3j.tx;
/*
 * Copyright 2019 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.besu.Besu;
import org.web3j.protocol.besu.crypto.crosschain.CrosschainRawTransaction;
import org.web3j.protocol.besu.crypto.crosschain.CrosschainTransactionEncoder;
import org.web3j.protocol.besu.crypto.crosschain.CrosschainTransactionType;
import org.web3j.protocol.besu.response.crosschain.CrossProcessSubordinateViewResponse;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.exceptions.TransactionException;
import org.web3j.tx.exceptions.TxHashMismatchException;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;
import org.web3j.tx.response.TransactionReceiptProcessor;
import org.web3j.utils.Numeric;

public class CrosschainTransactionManager extends RawTransactionManager {
    private final Besu besu;
    private final Credentials credentials;
    private final BigInteger chainId;
    private final Web3j coordinationBlockchain;
    private final BigInteger crosschainCoordinationBlockchainId;
    private final String crosschainCoordinationContractAddress;
    private final BigInteger crosschainTimeoutInBlocks;

    public CrosschainTransactionManager(
            final Besu besu,
            final Credentials credentials,
            final BigInteger chainId,
            final TransactionReceiptProcessor transactionReceiptProcessor,
            final Web3j coordinationBlockchain,
            final BigInteger crosschainCoordinationBlockchainId,
            final String crosschainCoordinationContractAddress,
            final long crosschainTimeoutInBlocks) {
        super(besu, credentials, chainId.longValue(), transactionReceiptProcessor);
        this.besu = besu;
        this.credentials = credentials;
        this.chainId = chainId;
        this.coordinationBlockchain = coordinationBlockchain;
        this.crosschainCoordinationBlockchainId = crosschainCoordinationBlockchainId;
        this.crosschainCoordinationContractAddress = crosschainCoordinationContractAddress;
        this.crosschainTimeoutInBlocks = BigInteger.valueOf(crosschainTimeoutInBlocks);
    }

    public CrosschainTransactionManager(
            final Besu besu,
            final Credentials credentials,
            final BigInteger chainId,
            final int attempts,
            final long sleepDuration,
            Web3j coordinationBlockchain,
            final BigInteger crosschainCoordinationBlockchainId,
            final String crosschainCoordinationContractAddress,
            final long crosschainTimeoutInBlocks) {
        this(
                besu,
                credentials,
                chainId,
                new PollingTransactionReceiptProcessor(besu, sleepDuration, attempts),
                coordinationBlockchain,
                crosschainCoordinationBlockchainId,
                crosschainCoordinationContractAddress,
                crosschainTimeoutInBlocks);
    }

    public CrosschainTransactionManager(
            final Besu besu,
            final Credentials credentials,
            final BigInteger chainId,
            final Web3j coordinationBlockchain,
            final BigInteger crosschainCoordinationBlockchainId,
            final String crosschainCoordinationContractAddress,
            final long crosschainTimeoutInBlocks) {
        this(
                besu,
                credentials,
                chainId,
                DEFAULT_POLLING_ATTEMPTS_PER_TX_HASH,
                DEFAULT_POLLING_FREQUENCY,
                coordinationBlockchain,
                crosschainCoordinationBlockchainId,
                crosschainCoordinationContractAddress,
                crosschainTimeoutInBlocks);
    }

    private byte[] createSignedCrosschainTransaction(
            CrosschainTransactionType type,
            BigInteger gasPrice,
            BigInteger gasLimit,
            String to,
            String data,
            BigInteger value,
            CrosschainContext crosschainContext)
            throws IOException {
        BigInteger nonce = getNonce();
        BigInteger currentBlockNumberOnCoordinationChain =
                this.coordinationBlockchain.ethBlockNumber().send().getBlockNumber();
        BigInteger crosschainTimeoutBlockNumber =
                currentBlockNumberOnCoordinationChain.add(this.crosschainTimeoutInBlocks);

        // The crosschain context information will be null if this is a single chain transaction,
        // for instance a lockable contract deploy with no subordinate transactions or views.
        if (crosschainContext != null) {
            crosschainContext.addCoordinationInformation(
                    this.crosschainCoordinationBlockchainId,
                    this.crosschainCoordinationContractAddress,
                    crosschainTimeoutBlockNumber);
        }

        CrosschainRawTransaction rawCrossChainTx =
                CrosschainRawTransaction.createTransaction(
                        type, nonce, gasPrice, gasLimit, to, value, data, crosschainContext);

        return CrosschainTransactionEncoder.signMessage(
                rawCrossChainTx, chainId.longValue(), credentials);
    }

    public byte[] createSignedSubordinateTransaction(
            BigInteger gasPrice,
            BigInteger gasLimit,
            String to,
            String data,
            BigInteger value,
            CrosschainContext crosschainContext)
            throws IOException {
        return createSignedCrosschainTransaction(
                CrosschainTransactionType.SUBORDINATE_TRANSACTION,
                gasPrice,
                gasLimit,
                to,
                data,
                value,
                crosschainContext);
    }

    public byte[] createSignedSubordinateDeployLockable(
            BigInteger gasPrice,
            BigInteger gasLimit,
            String data,
            BigInteger value,
            CrosschainContext crosschainContext)
            throws IOException {
        // Given this is a deploy, the to address is null.
        return createSignedCrosschainTransaction(
                CrosschainTransactionType.SUBORDINATE_DEPLOY_LOCKABLE,
                gasPrice,
                gasLimit,
                null,
                data,
                value,
                crosschainContext);
    }

    public byte[] createSignedSubordinateView(
            BigInteger gasPrice,
            BigInteger gasLimit,
            String to,
            String data,
            BigInteger value,
            CrosschainContext crosschainContext)
            throws IOException {
        return createSignedCrosschainTransaction(
                CrosschainTransactionType.SUBORDINATE_VIEW,
                gasPrice,
                gasLimit,
                to,
                data,
                value,
                crosschainContext);
    }

    public byte[] createSignedOriginatingTx(
            BigInteger gasPrice,
            BigInteger gasLimit,
            String to,
            String data,
            BigInteger value,
            CrosschainContext crosschainContext)
            throws IOException {
        return createSignedCrosschainTransaction(
                CrosschainTransactionType.ORIGINATING_TRANSACTION,
                gasPrice,
                gasLimit,
                to,
                data,
                value,
                crosschainContext);
    }

    public byte[] createSignedOriginatingDeployLockable(
            BigInteger gasPrice,
            BigInteger gasLimit,
            String data,
            BigInteger value,
            CrosschainContext crosschainContext)
            throws IOException {
        // Given this is a deploy, the to address is null.
        CrosschainTransactionType type =
                (crosschainContext == null)
                        ? CrosschainTransactionType.SINGLECHAIN_DEPLOY_LOCKABLE
                        : CrosschainTransactionType.ORIGINATING_DEPLOY_LOCKABLE;
        return createSignedCrosschainTransaction(
                type, gasPrice, gasLimit, null, data, value, crosschainContext);
    }

    public TransactionReceipt executeCrosschainTransaction(
            BigInteger gasPrice,
            BigInteger gasLimit,
            String to,
            String data,
            BigInteger value,
            CrosschainContext crosschainContext)
            throws IOException, TransactionException {

        byte[] signedMessage =
                createSignedOriginatingTx(gasPrice, gasLimit, to, data, value, crosschainContext);
        return executeTx(signedMessage);
    }

    public TransactionReceipt executeLockableContractDeploy(
            BigInteger gasPrice,
            BigInteger gasLimit,
            String data,
            BigInteger value,
            CrosschainContext crosschainContext)
            throws IOException, TransactionException {
        byte[] signedMessage =
                createSignedOriginatingDeployLockable(
                        gasPrice, gasLimit, data, value, crosschainContext);
        return executeTx(signedMessage);
    }

    private TransactionReceipt executeTx(final byte[] signedMessage)
            throws IOException, TransactionException {
        String hexValue = Numeric.toHexString(signedMessage);
        EthSendTransaction transactionResponse =
                this.besu.crossSendCrossChainRawTransaction(hexValue).send();

        if (transactionResponse != null && !transactionResponse.hasError()) {
            String txHashLocal = Hash.sha3(hexValue);
            String txHashRemote = transactionResponse.getTransactionHash();

            if (!txHashVerifier.verify(txHashLocal, txHashRemote)) {
                throw new TxHashMismatchException(txHashLocal, txHashRemote);
            }
        }

        if (transactionResponse.hasError()) {
            throw new RuntimeException(
                    "Error processing transaction request: "
                            + transactionResponse.getError().getMessage());
        }

        String transactionHash = transactionResponse.getTransactionHash();

        return transactionReceiptProcessor.waitForTransactionReceipt(transactionHash);
    }

    <T> T executeSubordinateView(
            BigInteger gasPrice,
            BigInteger gasLimit,
            String to,
            Function function,
            BigInteger value,
            CrosschainContext crosschainContext)
            throws IOException {

        String data = FunctionEncoder.encode(function);

        byte[] signedMessage =
                createSignedSubordinateView(gasPrice, gasLimit, to, data, value, crosschainContext);

        String hexValue = Numeric.toHexString(signedMessage);
        CrossProcessSubordinateViewResponse response =
                this.besu.crossProcessSubordinateView(hexValue).send();

        String retrunValue = response.getValue();
        List<Type> values =
                FunctionReturnDecoder.decode(retrunValue, function.getOutputParameters());

        if (!values.isEmpty()) {
            return (T) values.get(0);
        } else {
            return null;
        }
    }
}
