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
package org.web3j.protocol.besu.crypto.crosschain;
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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.web3j.crypto.Credentials;
import org.web3j.crypto.Sign;
import org.web3j.rlp.RlpEncoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;
import org.web3j.tx.CrosschainContext;
import org.web3j.utils.Bytes;
import org.web3j.utils.Numeric;

import static org.web3j.crypto.TransactionEncoder.createEip155SignatureData;
import static org.web3j.crypto.TransactionEncoder.toExactBytes;

public class CrosschainTransactionEncoder {

    public static byte[] signMessage(
            CrosschainRawTransaction rawTransaction, long chainId, Credentials credentials) {
        byte[] encodedTransaction = encode(rawTransaction, chainId);
        Sign.SignatureData signatureData =
                Sign.signMessage(encodedTransaction, credentials.getEcKeyPair());

        Sign.SignatureData eip155SignatureData = createEip155SignatureData(signatureData, chainId);
        return encode(rawTransaction, eip155SignatureData);
    }

    private static byte[] encode(CrosschainRawTransaction rawTransaction, long chainId) {
        Sign.SignatureData signatureData =
                new Sign.SignatureData(longToBytes(chainId), new byte[] {}, new byte[] {});
        // TODO Putting in the following line
        //                            new Sign.SignatureData(longToBytes(chainId), new byte[] {},
        // new byte[]
        // {});
        return encode(rawTransaction, signatureData);
    }

    private static byte[] longToBytes(final long chainId) {
        BigInteger chainIdLong = BigInteger.valueOf(chainId);
        return toExactBytes(chainIdLong);
    }

    private static byte[] encode(
            CrosschainRawTransaction rawTransaction, Sign.SignatureData signatureData) {
        List<RlpType> values = asRlpValues(rawTransaction, signatureData);
        RlpList rlpList = new RlpList(values);
        return RlpEncoder.encode(rlpList);
    }

    private static List<RlpType> asRlpValues(
            CrosschainRawTransaction rawTransaction, Sign.SignatureData signatureData) {
        CrosschainContext context = rawTransaction.getCrosschainContext();

        List<RlpType> result = new ArrayList<>();
        result.add(RlpString.create(rawTransaction.getType()));
        // This information isn't needed for crosschain transaction types that are just on a single
        // blockchain.
        if (context != null) {
            result.add(RlpString.create(context.getCrosschainCoordinationBlockchainId()));
            result.add(
                    RlpString.create(
                            Numeric.hexStringToByteArray(
                                    context.getCrosschainCoordinationContractAddress())));
            result.add(RlpString.create(context.getCrosschainTimeoutBlockNumber()));
            result.add(RlpString.create(context.getCrosschainTransactionId()));
            if (context.getFromSidechainId() != null) {
                // This information isn't needed for originating transactions.
                result.add(RlpString.create(context.getOriginatingSidechainId()));
                result.add(RlpString.create(context.getFromSidechainId()));
                result.add(
                        RlpString.create(Numeric.hexStringToByteArray(context.getFromAddress())));
            }
        }
        result.add(RlpString.create(rawTransaction.getNonce()));
        result.add(RlpString.create(rawTransaction.getGasPrice()));
        result.add(RlpString.create(rawTransaction.getGasLimit()));

        // an empty to address (contract creation) should not be encoded as a numeric 0 value
        String to = rawTransaction.getTo();
        if (to != null && to.length() > 0) {
            // addresses that start with zeros should be encoded with the zeros included, not
            // as numeric values
            result.add(RlpString.create(Numeric.hexStringToByteArray(to)));
        } else {
            result.add(RlpString.create(""));
        }

        result.add(RlpString.create(rawTransaction.getValue()));

        // value field will already be hex encoded, so we need to convert into binary first
        byte[] data = Numeric.hexStringToByteArray(rawTransaction.getData());
        result.add(RlpString.create(data));

        // If there are any subordinate transactions or views, add them here as an RLP Array.
        if (context != null) {
            byte[][] subordinateTransactionsAndViews = context.getSubordinateTransactionsAndViews();
            List<RlpType> rlpSubordinateTransactionsAndViews = new ArrayList<>();
            for (byte[] signedTransactionOrView : subordinateTransactionsAndViews) {
                rlpSubordinateTransactionsAndViews.add(RlpString.create(signedTransactionOrView));
            }
            RlpList rlpListSubordinateTransactionsAndViews =
                    new RlpList(rlpSubordinateTransactionsAndViews);
            result.add(rlpListSubordinateTransactionsAndViews);
        }

        if (signatureData != null) {
            result.add(RlpString.create(signatureData.getV()));
            result.add(RlpString.create(Bytes.trimLeadingZeroes(signatureData.getR())));
            result.add(RlpString.create(Bytes.trimLeadingZeroes(signatureData.getS())));
        }

        return result;
    }
}
