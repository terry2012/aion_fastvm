package org.aion.fastvm;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.math.BigInteger;

import java.util.Properties;
import org.aion.db.impl.DBVendor;
import org.aion.db.impl.DatabaseFactory;
import org.aion.interfaces.db.ContractDetails;
import org.aion.interfaces.db.PruneConfig;
import org.aion.interfaces.db.RepositoryConfig;
import org.aion.interfaces.vm.DataWord;
import org.aion.mcf.config.CfgPrune;
import org.aion.mcf.vm.types.DataWordImpl;
import org.aion.types.Address;
import org.aion.util.bytes.ByteUtil;
import org.aion.util.conversions.Hex;
import org.aion.contract.ContractUtils;
import org.aion.mcf.vm.types.KernelInterfaceForFastVM;
import org.aion.zero.impl.db.AionRepositoryCache;
import org.aion.zero.impl.db.AionRepositoryImpl;
import org.aion.zero.impl.db.ContractDetailsAion;
import org.apache.commons.lang3.RandomUtils;
import org.junit.Before;
import org.junit.Test;

public class DoSBlockGasLimitTest {

    private byte[] txHash = RandomUtils.nextBytes(32);
    private Address origin = Address.wrap(RandomUtils.nextBytes(32));
    private Address caller = origin;
    private Address address = Address.wrap(RandomUtils.nextBytes(32));

    private Address blockCoinbase = Address.wrap(RandomUtils.nextBytes(32));
    private long blockNumber = 1;
    private long blockTimestamp = System.currentTimeMillis() / 1000;
    private long blockNrgLimit = 5000000;
    private DataWord blockDifficulty = new DataWordImpl(0x100000000L);

    private DataWord nrgPrice;
    private long nrgLimit;
    private DataWord callValue;
    private byte[] callData;

    private int depth = 0;
    private int kind = ExecutionContext.CREATE;
    private int flags = 0;
    private AionRepositoryCache repo;


    @Before
    public void setup() {
        nrgPrice = DataWordImpl.ONE;
        nrgLimit = 100000;
        callValue = DataWordImpl.ZERO;
        callData = new byte[0];

        RepositoryConfig repoConfig =
            new RepositoryConfig() {
                @Override
                public String getDbPath() {
                    return "";
                }

                @Override
                public PruneConfig getPruneConfig() {
                    return new CfgPrune(false);
                }

                @Override
                public ContractDetails contractDetailsImpl() {
                    return ContractDetailsAion.createForTesting(0, 1000000).getDetails();
                }

                @Override
                public Properties getDatabaseConfig(String db_name) {
                    Properties props = new Properties();
                    props.setProperty(DatabaseFactory.Props.DB_TYPE, DBVendor.MOCKDB.toValue());
                    props.setProperty(DatabaseFactory.Props.ENABLE_HEAP_CACHE, "false");
                    return props;
                }
            };

        repo = new AionRepositoryCache(AionRepositoryImpl.createForTesting(repoConfig));
    }

    @Test
    public void testGasOverLimit() throws IOException {
        byte[] contract = ContractUtils.getContractBody("BlockGasLimit.sol", "BlockGasLimit");

        repo.saveCode(address, contract);

        BigInteger balance = BigInteger.valueOf(1000L);
        repo.addBalance(address, balance);

        callData = ByteUtil.merge(Hex.decode("e0aeb5e1"), new DataWordImpl(nrgLimit).getData());

        ExecutionContext ctx = newExecutionContext();
        FastVM vm = new FastVM();
        FastVmTransactionResult result =
                vm.run(contract, ctx, new KernelInterfaceForFastVM(
                    repo,
                    true,
                    false,
                    blockDifficulty,
                    blockNumber,
                    blockTimestamp,
                    blockNrgLimit,
                    blockCoinbase));
        System.out.println(result);
        assertEquals(FastVmResultCode.SUCCESS, result.getResultCode());
    }

    @Test
    public void testGasOverLimitFail1() throws IOException {
        byte[] contract = ContractUtils.getContractBody("BlockGasLimit.sol", "BlockGasLimit");

        repo.saveCode(address, contract);

        BigInteger balance = BigInteger.valueOf(1000L);
        repo.addBalance(address, balance);

        nrgLimit = 200;

        callData = ByteUtil.merge(Hex.decode("e0aeb5e1"), new DataWordImpl(nrgLimit).getData());

        ExecutionContext ctx = newExecutionContext();
        FastVM vm = new FastVM();
        FastVmTransactionResult result =
                vm.run(contract, ctx, new KernelInterfaceForFastVM(
                    repo,
                    true,
                    false,
                    blockDifficulty,
                    blockNumber,
                    blockTimestamp,
                    blockNrgLimit,
                    blockCoinbase));
        System.out.println(result);
        assertEquals(FastVmResultCode.OUT_OF_NRG, result.getResultCode());
    }

    @Test
    public void testGasOverLimitFail2() throws IOException {
        byte[] contract = ContractUtils.getContractBody("BlockGasLimit.sol", "BlockGasLimit");

        repo.saveCode(address, contract);

        BigInteger balance = BigInteger.valueOf(1000L);
        repo.addBalance(address, balance);

        nrgLimit = 405;

        callData = ByteUtil.merge(Hex.decode("e0aeb5e1"), new DataWordImpl(nrgLimit).getData());

        ExecutionContext ctx = newExecutionContext();
        FastVM vm = new FastVM();
        FastVmTransactionResult result =
                vm.run(contract, ctx, new KernelInterfaceForFastVM(
                    repo,
                    true,
                    false,
                    blockDifficulty,
                    blockNumber,
                    blockTimestamp,
                    blockNrgLimit,
                    blockCoinbase));
        System.out.println(result);
        assertEquals(FastVmResultCode.OUT_OF_NRG, result.getResultCode());
    }

    private ExecutionContext newExecutionContext() {
        return new ExecutionContext(
                null,
                txHash,
                address,
                origin,
                caller,
                nrgPrice,
                nrgLimit,
                callValue,
                callData,
                depth,
                kind,
                flags,
                blockCoinbase,
                blockNumber,
                blockTimestamp,
                blockNrgLimit,
                blockDifficulty);
    }
}
