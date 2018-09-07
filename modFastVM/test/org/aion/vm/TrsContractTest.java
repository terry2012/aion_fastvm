package org.aion.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.aion.base.db.IRepositoryCache;
import org.aion.base.type.Address;
import org.aion.base.util.ByteUtil;
import org.aion.base.util.Hex;
import org.aion.crypto.ECKey;
import org.aion.fastvm.TestVMProvider;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.aion.mcf.core.ImportResult;
import org.aion.mcf.vm.types.DataWord;
import org.aion.vm.AbstractExecutionResult.ResultCode;
import org.aion.zero.impl.BlockContext;
import org.aion.zero.impl.StandaloneBlockchain;
import org.aion.zero.impl.StandaloneBlockchain.Builder;
import org.aion.zero.impl.types.AionBlock;
import org.aion.zero.types.AionTransaction;
import org.apache.commons.lang3.RandomUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;

public class TrsContractTest {
    private static final Logger LOGGER_VM = AionLoggerFactory.getLogger(LogEnum.VM.toString());
    private static final long NRG = 1_000_000, NRG_PRICE = 1;
    private static BigInteger nonce;
    private StandaloneBlockchain blockchain;
    private ECKey deployerKey;
    private Address deployer;
    private BigInteger deployerBalance, deployerNonce;

    @Before
    public void setup() {
        StandaloneBlockchain.Bundle bundle = (new Builder())
            .withValidatorConfiguration("simple")
            .withDefaultAccounts()
            .build();
        blockchain = bundle.bc;
        deployerKey = bundle.privateKeys.get(0);
        deployer = new Address(deployerKey.getAddress());
        deployerBalance = Builder.DEFAULT_BALANCE;
        deployerNonce = BigInteger.ZERO;
        nonce = BigInteger.ZERO;
    }

    @After
    public void tearDown() {
        blockchain = null;
        deployerKey = null;
        deployer = null;
        deployerBalance = null;
        deployerNonce = null;
        nonce = null;
    }

    /**
     * Tests the query functionality isStart -- tells us whether the contract is currently live or
     * not.
     */
    @Test
    public void testIsStartQuery() {
        int periods = 4;
        int t0special = 1;
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, periods, t0special);
        assertFalse(TRSisStarted(trsContract, repo));
        lockTRScontract(trsContract, repo);
        startTRScontract(trsContract, repo);
        assertTrue(TRSisStarted(trsContract, repo));
    }

    /**
     * Tests the depositTo functionality -- deposits are fine before the contract is locked but
     * cannot be made after it is locked, including while live.
     */
    @Test
    public void testDeposits() {
        int periods = 4;
        int t0special = 1;
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, periods, t0special);

        int numAccounts = 1000;
        BigInteger accountBalances = new BigInteger("5000000");
        List<Address> accounts = makeAccounts(repo, accountBalances, numAccounts);
        depositIntoTRScontract(trsContract, repo, accounts, accountBalances);

        BigInteger expectedFacevalue = accountBalances.multiply(BigInteger.valueOf(numAccounts));
        assertEquals(expectedFacevalue, TRSgetTotalFacevalue(trsContract, repo));

        // Once account is locked we expect that no more deposits can be made.
        lockTRScontract(trsContract, repo);
        assertTrue(TRSisLocked(trsContract, repo));
        numAccounts = 200;
        accounts = makeAccounts(repo, accountBalances, numAccounts);
        depositIntoTRScontractWillFail(trsContract, repo, accounts, accountBalances);
        assertEquals(expectedFacevalue, TRSgetTotalFacevalue(trsContract, repo));

        // Now we start the contract and still expect no deposits can be made.
        startTRScontract(trsContract, repo);
        assertTrue(TRSisStarted(trsContract, repo));
        accounts = makeAccounts(repo, accountBalances, numAccounts);
        depositIntoTRScontractWillFail(trsContract, repo, accounts, accountBalances);
        assertEquals(expectedFacevalue, TRSgetTotalFacevalue(trsContract, repo));
    }

    @Test
    public void testWithdrawalsOverFullContractLifetime() {
        int periods = 4;
        int t0special = 1;
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, periods, t0special);

        BigInteger amount = BigInteger.TEN;
        Address depositor = makeAccount(repo, amount);
        depositIntoTRScontract(trsContract, repo, depositor, amount);

        BigInteger amt = TRSgetTotalFacevalue(trsContract, repo);
        assertEquals(BigInteger.TEN, amt);
    }

    //<----------------------------------------HELPERS--------------------------------------------->

    /**
     * Each account in depositors attempts to deposit amounts number of coins into the TRS contract
     * at address trsContract but we expect that the deposit should not succeed.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param depositors The depositors.
     * @param amounts the amounts for each account to attempt to deposit.
     */
    private void depositIntoTRScontractWillFail(Address trsContract, IRepositoryCache repo,
        List<Address> depositors, BigInteger amounts) {

        for (Address account : depositors) {
            assertTrue(repo.getBalance(account).compareTo(amounts) >= 0);
            sendCoinsToTRScontract(trsContract, repo, amounts);
            depositOfBehalfOf(trsContract, repo, account, amounts, false);
        }
    }

    /**
     * Each account in depositors deposits amounts number of coins into the TRS contract at address
     * trsContract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param depositors The depositors.
     * @param amounts The amounts for each account to deposit.
     */
    private void depositIntoTRScontract(Address trsContract, IRepositoryCache repo,
        List<Address> depositors, BigInteger amounts) {

        for (Address account : depositors) {
            assertTrue(repo.getBalance(account).compareTo(amounts) >= 0);
            sendCoinsToTRScontract(trsContract, repo, amounts);
            depositOfBehalfOf(trsContract, repo, account, amounts, true);
        }
    }

    /**
     * Depositor deposits amount number of coins into the TRS contract at address trsContract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param depositor The depositor.
     * @param amount The amount to deposit.
     */
    private void depositIntoTRScontract(Address trsContract, IRepositoryCache repo,
        Address depositor, BigInteger amount) {

        assertTrue(repo.getBalance(depositor).compareTo(amount) >= 0);
        sendCoinsToTRScontract(trsContract, repo, amount);
        depositOfBehalfOf(trsContract, repo, depositor, amount, true);
    }

    /**
     * Returns true if the TRS contract at address trsContract is locked.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @return true if contract is locked.
     */
    private boolean TRSisLocked(Address trsContract, IRepositoryCache repo) {
        byte[] input = Hex.decode("cf309012");
        BigInteger value = BigInteger.ZERO;
        nonce = nonce.add(BigInteger.ONE);
        AionTransaction tx = new AionTransaction(nonce.toByteArray(), trsContract, value.toByteArray(),
            input, NRG, NRG_PRICE);
        tx.sign(deployerKey);

        BlockContext context = blockchain.createNewBlockContext(blockchain.getBestBlock(),
            Collections.singletonList(tx), false);
        TransactionExecutor exec = new TransactionExecutor(tx, context.block, repo, LOGGER_VM);
        exec.setExecutorProvider(new TestVMProvider());
        exec.execute();
        ExecutionResult result = (ExecutionResult) exec.getResult();
        byte[] output = result.getOutput();
        return output[output.length - 1] == 0x1;
    }

    /**
     * Returns the total "face value" amount of coins in the TRS contract. This is the amount of
     * coins that have been deposited into the contract by users.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @return the total amount of deposited coins.
     */
    private BigInteger TRSgetTotalFacevalue(Address trsContract, IRepositoryCache repo) {
        byte[] input = Hex.decode("c3af702e");
        BigInteger value = BigInteger.ZERO;
        nonce = nonce.add(BigInteger.ONE);
        AionTransaction tx = new AionTransaction(nonce.toByteArray(), trsContract, value.toByteArray(),
            input, NRG, NRG_PRICE);
        tx.sign(deployerKey);

        BlockContext context = blockchain.createNewBlockContext(blockchain.getBestBlock(),
            Collections.singletonList(tx), false);
        TransactionExecutor exec = new TransactionExecutor(tx, context.block, repo, LOGGER_VM);
        exec.setExecutorProvider(new TestVMProvider());
        exec.execute();
        ExecutionResult result = (ExecutionResult) exec.getResult();
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        return new BigInteger(result.getOutput());
    }

    /**
     * Deposits amount number of coins into the TRS contract at trsContract address and associates
     * them with beneficiary.
     *
     * Note: the contract must possess these coins already. So beneficiary should already have
     * sent them.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param beneficiary The beneficiary of the deposit.
     * @param amount The amount to assign.
     */
    private void depositOfBehalfOf(Address trsContract, IRepositoryCache repo, Address beneficiary,
        BigInteger amount, boolean willSucceed) {

        byte[] input = ByteUtil.merge(Hex.decode("184274fc"), beneficiary.toBytes());
        input = ByteUtil.merge(input, new DataWord(amount).getData());
        BigInteger value = BigInteger.ZERO;
        nonce = nonce.add(BigInteger.ONE);
        AionTransaction tx = new AionTransaction(nonce.toByteArray(), trsContract, value.toByteArray(),
            input, NRG, NRG_PRICE);
        tx.sign(deployerKey);

        BlockContext context = blockchain.createNewBlockContext(blockchain.getBestBlock(),
            Collections.singletonList(tx), false);
        TransactionExecutor exec = new TransactionExecutor(tx, context.block, repo, LOGGER_VM);
        exec.setExecutorProvider(new TestVMProvider());
        exec.execute();
        ExecutionResult result = (ExecutionResult) exec.getResult();

        if (willSucceed) {
            assertEquals(ResultCode.SUCCESS, result.getResultCode());
            System.err.println("TRS contract deposited " + amount + " coins onbehalf of " + beneficiary);
        } else {
            assertNotEquals(ResultCode.SUCCESS, result.getResultCode());
        }

    }

    /**
     * Sends amount coins to the TRS contract at address trsContract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param amount The amount to send.
     */
    private void sendCoinsToTRScontract(Address trsContract, IRepositoryCache repo, BigInteger amount) {
        assertTrue(repo.getBalance(deployer).compareTo(amount) >= 0);
        BigInteger contractPrevBalance = repo.getBalance(trsContract);
        nonce = nonce.add(BigInteger.ONE);
        AionTransaction tx = new AionTransaction(nonce.toByteArray(), trsContract,
            amount.toByteArray(), new byte[1], NRG, NRG_PRICE);
        tx.sign(deployerKey);

        BlockContext context = blockchain.createNewBlockContext(blockchain.getBestBlock(),
            Collections.singletonList(tx), false);
        TransactionExecutor exec = new TransactionExecutor(tx, context.block, repo, LOGGER_VM);
        exec.setExecutorProvider(new TestVMProvider());
        exec.execute();
        ExecutionResult result = (ExecutionResult) exec.getResult();
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        assertEquals(contractPrevBalance.add(amount), repo.getBalance(trsContract));
    }

    /**
     * Returns the amount of funds remaining in the TRS contract at address trsContract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @return the amount of funds remaining in contract.
     */
    private BigInteger TRSgetRemainder(Address trsContract, IRepositoryCache repo) {
        byte[] input = Hex.decode("a0684251");
        BigInteger value = BigInteger.ZERO;
        nonce = nonce.add(BigInteger.ONE);
        AionTransaction tx = new AionTransaction(nonce.toByteArray(), trsContract, value.toByteArray(),
            input, NRG, NRG_PRICE);
        tx.sign(deployerKey);

        BlockContext context = blockchain.createNewBlockContext(blockchain.getBestBlock(),
            Collections.singletonList(tx), false);
        TransactionExecutor exec = new TransactionExecutor(tx, context.block, repo, LOGGER_VM);
        exec.setExecutorProvider(new TestVMProvider());
        exec.execute();
        ExecutionResult result = (ExecutionResult) exec.getResult();
        return new BigInteger(result.getOutput());
    }

    /**
     * Starts (or makes live) the TRS contract at address trsContract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     */
    private void startTRScontract(Address trsContract, IRepositoryCache repo) {
        long startTime = blockchain.getBestBlock().getTimestamp();
        byte[] input = ByteUtil.merge(Hex.decode("2392b0f0"), new DataWord(startTime).getData());
        BigInteger value = BigInteger.ZERO;
        nonce = nonce.add(BigInteger.ONE);
        AionTransaction tx = new AionTransaction(nonce.toByteArray(), trsContract, value.toByteArray(),
            input, NRG, NRG_PRICE);
        tx.sign(deployerKey);

        BlockContext context = blockchain.createNewBlockContext(blockchain.getBestBlock(),
            Collections.singletonList(tx), false);
        TransactionExecutor exec = new TransactionExecutor(tx, context.block, repo, LOGGER_VM);
        exec.setExecutorProvider(new TestVMProvider());
        exec.execute();
        ExecutionResult result = (ExecutionResult) exec.getResult();
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        System.err.println("TRS contract has been started.");
    }

    /**
     * Locks the TRS contract at address trsContract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     */
    private void lockTRScontract(Address trsContract, IRepositoryCache repo) {
        byte[] input = org.aion.base.util.Hex.decode("f83d08ba");
        BigInteger value = BigInteger.ZERO;
        nonce = nonce.add(BigInteger.ONE);
        AionTransaction tx = new AionTransaction(nonce.toByteArray(), trsContract, value.toByteArray(),
            input, NRG, NRG_PRICE);
        tx.sign(deployerKey);

        BlockContext context = blockchain.createNewBlockContext(blockchain.getBestBlock(),
            Collections.singletonList(tx), false);
        TransactionExecutor exec = new TransactionExecutor(tx, context.block, repo, LOGGER_VM);
        exec.setExecutorProvider(new TestVMProvider());
        exec.execute();
        ExecutionResult result = (ExecutionResult) exec.getResult();
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        System.err.println("The TRS contract has been locked.");
    }

    /**
     * Returns true iff the TRS contract at address trsContract is live (has started).
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @return true if contract is live.
     */
    private boolean TRSisStarted(Address trsContract, IRepositoryCache repo) {
        byte[] input = Hex.decode("544736e6");
        BigInteger value = BigInteger.ZERO;
        nonce = nonce.add(BigInteger.ONE);
        AionTransaction tx = new AionTransaction(nonce.toByteArray(), trsContract, value.toByteArray(),
            input, NRG, NRG_PRICE);
        tx.sign(deployerKey);

        BlockContext context = blockchain.createNewBlockContext(blockchain.getBestBlock(),
            Collections.singletonList(tx), false);
        TransactionExecutor exec = new TransactionExecutor(tx, context.block, repo, LOGGER_VM);
        exec.setExecutorProvider(new TestVMProvider());
        exec.execute();
        ExecutionResult result = (ExecutionResult) exec.getResult();
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        byte[] output = result.getOutput();
        return output[output.length - 1] == 0x1;
    }

    /**
     * Initializes the TRS contract at address trsContract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param periods Number of periods for the TRS contract.
     * @param t0special The one-off special multiplier.
     */
    private void initTRScontract(Address trsContract, IRepositoryCache repo, int periods, int t0special) {
        // initialize the contract the contract.
        byte[] input = ByteUtil.merge(Hex.decode("a191fe28"), new DataWord(periods).getData());
        input = ByteUtil.merge(input, new DataWord(t0special).getData());
        BigInteger value = BigInteger.ZERO;
        nonce = nonce.add(BigInteger.ONE);
        AionTransaction tx = new AionTransaction(nonce.toByteArray(), trsContract, value.toByteArray(),
            input, NRG, NRG_PRICE);
        tx.sign(deployerKey);

        BlockContext context = blockchain.createNewBlockContext(blockchain.getBestBlock(),
            Collections.singletonList(tx), false);
        TransactionExecutor exec = new TransactionExecutor(tx, context.block, repo, LOGGER_VM);
        exec.setExecutorProvider(new TestVMProvider());
        exec.execute();
        ExecutionResult result = (ExecutionResult) exec.getResult();
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        System.err.println("TRS contract successfully initialized.");

        // finalize the contract init.
        input = Hex.decode("72a02f1d");
        nonce = nonce.add(BigInteger.ONE);
        tx = new AionTransaction(nonce.toByteArray(), trsContract, value.toByteArray(),
            input, NRG, NRG_PRICE);
        tx.sign(deployerKey);

        context = blockchain.createNewBlockContext(blockchain.getBestBlock(),
            Collections.singletonList(tx), false);
        exec = new TransactionExecutor(tx, context.block, repo, LOGGER_VM);
        exec.setExecutorProvider(new TestVMProvider());
        exec.execute();
        result = (ExecutionResult) exec.getResult();
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        System.err.println("TRS contract initialization successfully finalized.");
    }

    /**
     * Returns the address of the account that holds the TRS contract code after deploying that
     * contract.
     *
     * @return the TRS contract address.
     */
    private Address deployTRScontract(IRepositoryCache repo) {
        Address contract = deployContract(repo, getDeployTx());
        addBlock();
        return contract;
    }

    private AionTransaction getDeployTx() {
        byte[] deployCode = Hex.decode(TRSdeployCode());
        BigInteger value = BigInteger.ZERO;
        AionTransaction tx = new AionTransaction(nonce.toByteArray(), null, value.toByteArray(),
            deployCode, NRG, NRG_PRICE);
        tx.sign(deployerKey);
        return tx;
    }

    /**
     * Deploys a contract named contractName and checks the state of the deployed contract and the
     * contract deployer and returns the address of the contract once finished.
     */
    private Address deployContract(IRepositoryCache repo, AionTransaction tx) {
        tx.sign(deployerKey);
        assertTrue(tx.isContractCreation());
        assertEquals(deployerBalance, repo.getBalance(deployer));
        assertEquals(deployerNonce, repo.getNonce(deployer));

        BlockContext context = blockchain.createNewBlockContext(blockchain.getBestBlock(),
            Collections.singletonList(tx), false);
        TransactionExecutor exec = new TransactionExecutor(tx, context.block, repo, LOGGER_VM);
        exec.setExecutorProvider(new TestVMProvider());
        exec.execute();
        ExecutionResult result = (ExecutionResult) exec.getResult();
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        assertEquals(NRG - tx.getNrgConsume(), result.getNrgLeft());
        assertNotEquals(NRG, tx.getNrgConsume());

        return tx.getContractAddress();
    }

    /**
     * Deploys inside a block and adds to chain.
     */
    private Address deployContractInBlock(IRepositoryCache repo, AionTransaction tx) {
        tx.sign(deployerKey);
        assertTrue(tx.isContractCreation());
        assertEquals(deployerBalance, repo.getBalance(deployer));
        assertEquals(deployerNonce, repo.getNonce(deployer));

        AionBlock block = blockchain.createNewBlock(
            blockchain.getBestBlock(),
            Collections.singletonList(tx),
            false);
        assertEquals(ImportResult.IMPORTED_BEST, blockchain.tryToConnect(block));

        return tx.getContractAddress();
    }

    /**
     * Returns a Hex String of the deployment code for the TRS contract.
     * @return
     */
    private String TRSdeployCode() {
        return "6050604052600060076000509090555b3360006000508282909180600101839055555050505b61002a565b6116c3806100396000396000f300605060405236156101d5576000356c01000000000000000000000000900463ffffffff168063144fa6d7146101d9578063184274fc146102055780632392b0f01461023a5780632ddbd13a1461025e5780632ed94f6c14610288578063346a76e7146102e75780633a63aa8f146103465780633ccfd60b1461039957806343c885ba146103c75780634cbf867d146103f557806354469aea1461041f578063544736e6146104435780636ef610921461047157806372a02f1d146104b157806372b0d90c146104c757806377b7aa2c1461050b5780637965f1111461054357806379ba5097146105ab5780638da5cb5b146105c157806390e2b94b146105f2578063a06842511461061c578063a191fe2814610646578063a4caeb4214610673578063a6f9dae11461069d578063ac3dc9aa146106c9578063c255fa40146106f3578063c3af702e14610709578063cb13cddb14610733578063cf30901214610773578063d3b5dc3b146107a1578063d4ee1d90146107cb578063dd39472f146107fc578063e6c2776e14610834578063ece20f3614610869578063ef78d4fd1461087f578063f83d08ba146108a9578063f9df65eb146108bf578063fbb0eb8b146108ed578063fc0c546a14610917576101d5565b5b5b005b34156101e55760006000fd5b61020360048080806010013590359091602001909192905050610948565b005b34156102115760006000fd5b61023860048080806010013590359091602001909192908035906010019091905050610980565b005b34156102465760006000fd5b61025c6004808035906010019091905050610a71565b005b341561026a5760006000fd5b610272610b0c565b6040518082815260100191505060405180910390f35b34156102945760006000fd5b6102e560048080359060100190820180359060100191919080806020026010016040519081016040528093929190818152601001838360200280828437820191505050505050909091905050610b15565b005b34156102f35760006000fd5b61034460048080359060100190820180359060100191919080806010026010016040519081016040528093929190818152601001838360100280828437820191505050505050909091905050610b83565b005b34156103525760006000fd5b6103836004808035906010019091908035906010019091908035906010019091908035906010019091905050610c35565b6040518082815260100191505060405180910390f35b34156103a55760006000fd5b6103ad610c9b565b604051808215151515815260100191505060405180910390f35b34156103d35760006000fd5b6103db610cd4565b604051808215151515815260100191505060405180910390f35b34156104015760006000fd5b610409610ce7565b6040518082815260100191505060405180910390f35b341561042b5760006000fd5b6104416004808035906010019091905050610cec565b005b341561044f5760006000fd5b610457610d3f565b604051808215151515815260100191505060405180910390f35b341561047d5760006000fd5b61049b60048080806010013590359091602001909192905050610d6d565b6040518082815260100191505060405180910390f35b34156104bd5760006000fd5b6104c5610d8f565b005b34156104d35760006000fd5b6104f160048080806010013590359091602001909192905050610dec565b604051808215151515815260100191505060405180910390f35b34156105175760006000fd5b61052d6004808035906010019091905050611003565b6040518082815260100191505060405180910390f35b341561054f5760006000fd5b6105a96004808035906010019091908035906010019082018035906010019191908080601002601001604051908101604052809392919081815260100183836010028082843782019150505050505090909190505061105c565b005b34156105b75760006000fd5b6105bf6111e0565b005b34156105cd5760006000fd5b6105d561126d565b604051808383825281601001526020019250505060405180910390f35b34156105fe5760006000fd5b61060661127c565b6040518082815260100191505060405180910390f35b34156106285760006000fd5b610630611285565b6040518082815260100191505060405180910390f35b34156106525760006000fd5b610671600480803590601001909190803590601001909190505061128e565b005b341561067f5760006000fd5b6106876112fb565b6040518082815260100191505060405180910390f35b34156106a95760006000fd5b6106c760048080806010013590359091602001909192905050611304565b005b34156106d55760006000fd5b6106dd611340565b6040518082815260100191505060405180910390f35b34156106ff5760006000fd5b610707611349565b005b34156107155760006000fd5b61071d611453565b6040518082815260100191505060405180910390f35b341561073f5760006000fd5b61075d6004808080601001359035909160200190919290505061145c565b6040518082815260100191505060405180910390f35b341561077f5760006000fd5b61078761147e565b604051808215151515815260100191505060405180910390f35b34156107ad5760006000fd5b6107b5611491565b6040518082815260100191505060405180910390f35b34156107d75760006000fd5b6107df61149d565b604051808383825281601001526020019250505060405180910390f35b34156108085760006000fd5b61081e60048080359060100190919050506114ac565b6040518082815260100191505060405180910390f35b34156108405760006000fd5b610867600480808060100135903590916020019091929080359060100190919050506114f1565b005b34156108755760006000fd5b61087d6115d3565b005b341561088b5760006000fd5b610893611612565b6040518082815260100191505060405180910390f35b34156108b55760006000fd5b6108bd61162d565b005b34156108cb5760006000fd5b6108d361166c565b604051808215151515815260100191505060405180910390f35b34156108f95760006000fd5b61090161167f565b6040518082815260100191505060405180910390f35b34156109235760006000fd5b61092b611688565b604051808383825281601001526020019250505060405180910390f35b600060005080600101549054339091149190141615156109685760006000fd5b8181600860005082828255906001015550505b5b5050565b600060005080600101549054339091149190141615156109a05760006000fd5b600660019054906101000a900460ff161580156109c257506000600760005054145b15156109ce5760006000fd5b600f60009054906101000a900460ff161515156109eb5760006000fd5b80600a60005060008585825281601001526020019081526010016000209050600082828250540192505081909090555080600b600082828250540192505081909090555082827fc6dcd8d437d8b3537583463d84a6ba9d7e3e013fa4e004da9b6dee1482038be5846040518082815260100191505060405180910390a25b5b5b5b505050565b600060006000508060010154905433909114919014161515610a935760006000fd5b600660009054906101000a900460ff161515610aaf5760006000fd5b600660019054906101000a900460ff168015610ad057506000600760005054145b1515610adc5760006000fd5b8160076000508190909055503031905080600d60005081909090555080600c6000508190909055505b5b5b5b5050565b600d6000505481565b6000600f60009054906101000a900460ff16151515610b345760006000fd5b600090505b8151811015610b7d57610b6e8282815181101515610b5357fe5b90601001906020020180601001519051610dec63ffffffff16565b505b8080600101915050610b39565b5b5b5050565b6000600060006000600060006000508060010154905433909114919014161515610bad5760006000fd5b6bffffffffffffffffffffffff9450600093505b8551841015610c2b5760608685815181101515610bda57fe5b906010019060100201519060020a9004600092509250848685815181101515610bff57fe5b90601001906010020151169050610c1d83838361098063ffffffff16565b5b8380600101945050610bc1565b5b5b505050505050565b600060006000610c4a856114ac63ffffffff16565b9150670de0b6b3a7640000600b6000505485848a0202811515610c6957fe5b04811515610c7357fe5b04905085811115610c88578581039250610c91565b60009250610c91565b5050949350505050565b6000600f60009054906101000a900460ff16151515610cba5760006000fd5b610cc933610dec63ffffffff16565b9050610cd0565b5b90565b600660009054906101000a900460ff1681565b600381565b60006000508060010154905433909114919014161515610d0c5760006000fd5b600f60009054906101000a900460ff16151515610d295760006000fd5b610d39338361098063ffffffff16565b5b5b5b50565b6000600660019054906101000a900460ff168015610d635750600060076000505414155b9050610d6a565b90565b600e600050602052818160005260105260306000209050600091509150505481565b60006000508060010154905433909114919014161515610daf5760006000fd5b600660009054906101000a900460ff16151515610dcc5760006000fd5b6001600660006101000a81548160ff0219169083151502179055505b5b5b565b6000600060006000600660019054906101000a900460ff168015610e165750600060076000505414155b1515610e225760006000fd5b600f60009054906101000a900460ff16151515610e3f5760006000fd5b600a60005060008787825281601001526020019081526010016000209050600050549250600e60005060008787825281601001526020019081526010016000209050600050549150610e9e838342600d60005054610c3563ffffffff16565b90506000811415610eb25760009350610ff8565b600b60005054600d600050548402811515610ec957fe5b0482820111151515610edb5760006000fd5b60086000508060010154905463fbb001d68888856000604051601001526040518463ffffffff166c010000000000000000000000000281526004018084848252816010015260200182815260100193505050506010604051808303816000888881813b1515610f4a5760006000fd5b5af11515610f585760006000fd5b50505050604051805190601001501515610f725760006000fd5b80600e60005060008888825281601001526020019081526010016000209050600082828250540192505081909090555080600c600082828250540392505081909090555085857fb061022b0142dafc69e0206f0d1602f87e19faa0bd2befbf1d557f50a0dbb78e846040518082815260100191505060405180910390a260019350610ff8565b5b5b50505092915050565b6000600082600760005054111561101d5760009150611056565b60016003600760005054850381151561103257fe5b0401905060046000505481111561104e57600460005054905080505b809150611056565b50919050565b600060006000600060006000600060005080600101549054339091149190141615156110885760006000fd5b600660019054906101000a900460ff161580156110aa57506000600760005054145b15156110b65760006000fd5b601060005054881415156110c9576111d4565b6001601060008282825054019250508190909055506bffffffffffffffffffffffff955060009450600093505b86518410156111bf576060878581518110151561110f57fe5b906010019060100201519060020a900460009250925085878581518110151561113457fe5b9060100190601002015116905080600a6000506000858582528160100152602001908152601001600020905060008282825054019250508190909055508085019450845082827fc6dcd8d437d8b3537583463d84a6ba9d7e3e013fa4e004da9b6dee1482038be5846040518082815260100191505060405180910390a25b83806001019450506110f6565b84600b60008282825054019250508190909055505b5b5b5050505050505050565b60026000508060010154905433909114919014161561126a5760026000508060010154905460006000508282909180600101839055555050506000600060026000508282909180600101839055555050506000600050806001015490547fa701229f4b9ddf00aa1c7228d248e6320ee7c581d856ddfba036e73947cd0d1360405160405180910390a25b5b565b60006000508060010154905482565b60056000505481565b600c6000505481565b600060005080600101549054339091149190141615156112ae5760006000fd5b600660009054906101000a900460ff161515156112cb5760006000fd5b600082141515156112dc5760006000fd5b8160046000508190909055508060056000508190909055505b5b5b5050565b60046000505481565b600060005080600101549054339091149190141615156113245760006000fd5b818160026000508282909180600101839055555050505b5b5050565b60076000505481565b600060006000600050806001015490543390911491901416151561136d5760006000fd5b600660019054906101000a900460ff1615156113895760006000fd5b6008600050806001015490546370a08231306000604051601001526040518363ffffffff166c0100000000000000000000000002815260040180838382528160100152602001925050506010604051808303816000888881813b15156113ef5760006000fd5b5af115156113fd5760006000fd5b50505050604051805190601001509150600c6000505482101515156114225760006000fd5b600c600050548203905080600d600082828250540192505081909090555081600c6000508190909055505b5b5b5050565b600b6000505481565b600a600050602052818160005260105260306000209050600091509150505481565b600660019054906101000a900460ff1681565b670de0b6b3a764000081565b60026000508060010154905482565b600060046000505460056000505401670de0b6b3a76400006114d38461100363ffffffff16565b60056000505401028115156114e457fe5b0490506114ec565b919050565b600060005080600101549054339091149190141615156115115760006000fd5b600660019054906101000a900460ff1615801561153357506000600760005054145b151561153f5760006000fd5b60086000508060010154905463fbb001d68585856000604051601001526040518463ffffffff166c010000000000000000000000000281526004018084848252816010015260200182815260100193505050506010604051808303816000888881813b15156115ae5760006000fd5b5af115156115bc5760006000fd5b5050505060405180519060100150505b5b5b505050565b600060005080600101549054339091149190141615156115f35760006000fd5b6001600f60006101000a81548160ff0219169083151502179055505b5b565b60006116234261100363ffffffff16565b905061162a565b90565b6000600050806001015490543390911491901416151561164d5760006000fd5b6001600660016101000a81548160ff0219169083151502179055505b5b565b600f60009054906101000a900460ff1681565b60106000505481565b600860005080600101549054825600a165627a7a723058205dadd13bd9534e3e37b70909f0d143a25fd37c25a86f76d9829e6e3b9775b8030029";
    }

    /**
     * Adds a new block to the blockchain.
     */
    private void addBlock() {
        AionBlock block = blockchain.createNewBlock(
            blockchain.getBestBlock(),
            new ArrayList<>(),
            false);
        assertEquals(ImportResult.IMPORTED_BEST, blockchain.tryToConnect(block));
    }

    /**
     * Returns a list of numAccounts new random accounts, each of which is saved in repo with
     * initial balances of initBalances.
     *
     * @param repo The repo.
     * @param initBalances The initial balances for each account.
     * @param numAccounts The number of new accounts to make.
     * @return a list of new accounts.
     */
    private List<Address> makeAccounts(IRepositoryCache repo, BigInteger initBalances, int numAccounts) {
        List<Address> accounts = new ArrayList<>(numAccounts);
        for (int i = 0; i < numAccounts; i++) {
            accounts.add(makeAccount(repo, initBalances));
        }
        return accounts;
    }

    /**
     * Creates a new random address and stores it in repo with initial balance of balance.
     *
     * @param repo The repo.
     * @param balance The initial account balance.
     * @return a new random address.
     */
    private Address makeAccount(IRepositoryCache repo, BigInteger balance) {
        Address account = new Address(RandomUtils.nextBytes(Address.ADDRESS_LEN));
        repo.createAccount(account);
        repo.addBalance(account, balance);
        return account;
    }

}