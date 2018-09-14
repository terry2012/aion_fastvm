package org.aion.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
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
import org.aion.mcf.vm.Constants;
import org.aion.mcf.vm.types.DataWord;
import org.aion.solidity.SolidityType;
import org.aion.solidity.SolidityType.DynamicArrayType;
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
    private static final BigInteger DEFAULT_BALANCE = new BigInteger("1000000000");
    private static long NRG = 1_000_000, NRG_PRICE = 1;
    private StandaloneBlockchain blockchain;
    private ECKey deployerKey;
    private Address deployer;
    private BigInteger deployerBalance, deployerNonce;

    //TODO: would greatly appreciate another pair of eyes on these numbers for validations..
    // NOTE -- if you change the Savings.sol file then update the TRSdeployCode() method with the
    // correct binary!

    // THIS BLOCK OF FIELDS CORRESPONDS TO THE 4 ADDRESSES IN THE ACTUAL ETHEREUM TRS CONTRACT.
    private static final String ACCOUNT_1 = "b493c9C0C0aBfd9847baB53231774f13BF882eE9";
    private static final String ACCOUNT_2 = "0e24D8Fcdf0c319dF03998Cc53F4FBA035D9a4f9";
    private static final String ACCOUNT_3 = "8567462b8E8303637F0004B2E664993314e58BD7";
    private static final String ACCOUNT_4 = "4778bE92Dd5c51035bf80Fca564ba5E7Fad5FB6d";

    // THIS BLOCK OF FIELDS CORRESPONDS TO THE ACTUAL ETHEREUM TRS CONTRACT THAT IS DEPLOYED.
    private static final BigInteger ETH_TOTAL = new BigInteger("30489196119340505");
    private static final BigInteger ETH_STARTTIME = new BigInteger("1513627200");
    private static final BigInteger ETH_TOTALFV = new BigInteger("18491381166400396");
    private static final BigInteger ETH_BONUS = ETH_TOTAL.subtract(ETH_TOTALFV);
    private static final BigInteger ETH_PERIODS = new BigInteger("36");
    private static final BigInteger ETH_T0SPECIAL = BigInteger.ZERO;
    private static final BigInteger ETH_PRECISION = BigInteger.TEN.pow(18);
    private static final BigInteger DEPOSITED_1 = new BigInteger("3581474393280396");
    private static final BigInteger DEPOSITED_2 = new BigInteger("9318691733200000");
    private static final BigInteger DEPOSITED_3 = new BigInteger("2795607519960000");
    private static final BigInteger DEPOSITED_4 = new BigInteger("2795607519960000");
    private static final BigInteger WITHDRAWN_1 = new BigInteger("1476313129215250");
    private static final BigInteger WITHDRAWN_2 = new BigInteger("3841241187887422");
    private static final BigInteger WITHDRAWN_3 = new BigInteger("1152372356366226");
    private static final BigInteger WITHDRAWN_4 = new BigInteger("1152372356366226");

    // THIS BLOCK OF FIELDS ARE THE AMOUNTS EACH ACCOUNT WILL HAVE WITHDRAWN ON ETHEREUM SIDE IN PERIOD 12.
    private static final BigInteger LAST_WITHDRAW_PERIOD = new BigInteger("12");
    private static final BigInteger LAST_WITHDRAW_1 = new BigInteger("1968417505620333");
    private static final BigInteger LAST_WITHDRAW_2 = new BigInteger("5121654917183230");
    private static final BigInteger LAST_WITHDRAW_3 = new BigInteger("1536496475154969");
    private static final BigInteger LAST_WITHDRAW_4 = new BigInteger("1536496475154969");

    // THIS BLOCK OF FIELDS CORRESPONDS TO OUR AION TRS CONTRACT, AFTER ETHEREUM HAS BEEN MIGRATED OVER.
    private static final BigInteger AION_TOTAL = new BigInteger("20326130746227004");
    private static final BigInteger AION_STARTTIME = new BigInteger("0");
    private static final BigInteger AION_TOTALFV = new BigInteger("8328315793286895");
    private static final BigInteger AION_PERIODS = new BigInteger("24");
    private static final BigInteger AION_T0SPECIAL = BigInteger.ZERO;
    private static final BigInteger AION_DEPOSITED_1 = new BigInteger("1613056887660063");
    private static final BigInteger AION_DEPOSITED_2 = new BigInteger("4197036816016770");
    private static final BigInteger AION_DEPOSITED_3 = new BigInteger("1259111044805031");
    private static final BigInteger AION_DEPOSITED_4 = new BigInteger("1259111044805031");

    // AION_CONVERSION = x specifies the exponent (ie. 10^x) by which to augment the ethereum figures.
    private static BigInteger AION_CONVERSION = BigInteger.ONE;
    /**
     * Sets the number of left shifts to perform on the ethereum token amounts when converting them
     * over to Aion coins.
     *
     * The actual difference between these amounts is 10^10, but this number causes overflow in the
     * contract.
     *
     * The solution is to reduce this conversion down to 10^x for some x < 10 and then in the
     * contract, once the withdrawable amount is computed, to multiply it by 10^y such that x+y=10
     * so that the 10^10 conversion does in fact take place.
     */
    private void setAionConversion(int conversion) {
        AION_CONVERSION = BigInteger.valueOf(conversion);
    }

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
    }

    @After
    public void tearDown() {
        blockchain = null;
        deployerKey = null;
        deployer = null;
        deployerBalance = null;
        deployerNonce = null;
    }

    /**
     * Tests we can query the start time of the contract.
     */
    @Test
    public void testStartTimeQuery() {
        int periods = 4;
        int t0special = 1;
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, periods, t0special);

        // Before the contract starts the start time should be zero.
        assertEquals(0, TRSgetStartTime(trsContract, repo));
        lockTRScontract(trsContract, repo);
        assertEquals(0, TRSgetStartTime(trsContract, repo));

        // Contract is live and uses timestamp of current best block.
        startTRScontract(trsContract, repo);
        assertEquals(blockchain.getBestBlock().getTimestamp(), TRSgetStartTime(trsContract, repo));
    }

    /**
     * Tests we can query the number of periods in the contract.
     */
    @Test
    public void testPeriodsQuery() {
        int periods = RandomUtils.nextInt(10, 100_000);
        int t0special = 1;
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, periods, t0special);
        assertEquals(periods, TRSgetNumPeriods(trsContract, repo));
    }

    /**
     * Tests we can query the current period of the contract.
     */
    @Test
    public void testPeriodQuery() {
        int periods = 100;
        int t0special = 1;
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, periods, t0special);
        lockTRScontract(trsContract, repo);
        startTRScontract(trsContract, repo);

        int expectedPeriod = 1;
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        int thisInterval;
        for (int i = 0; i < periods; i++) {
            // First period is treated differently because we begin at timestamp 1 not 0 so there is
            // one less second in this period compared to the others.
            thisInterval = (i == 0) ? periodInterval - 1 : periodInterval;
            for (int j = 0; j < thisInterval; j++) {
                assertEquals(expectedPeriod, TRSgetCurrentPeriod(trsContract, repo));
                addBlock(blockchain.getBestBlock().getTimestamp() + 1);
            }
            expectedPeriod++;
        }
    }

    /**
     * Tests we can query the special one-off multiplier.
     */
    @Test
    public void testSpecialMultiplierQuery() {
        int periods = 4;
        int t0special = RandomUtils.nextInt(0, 100_000);

        IRepositoryCache repo = blockchain.getRepository().startTracking();

        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, periods, t0special);
        assertEquals(t0special, TRSgetT0special(trsContract, repo));
    }

    /**
     * Tests we can query whether the contract is locked or not.
     */
    @Test
    public void testIsLockedQuery() {
        int periods = 4;
        int t0special = 1;
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, periods, t0special);
        assertFalse(TRSisLocked(trsContract, repo));

        lockTRScontract(trsContract, repo);
        assertTrue(TRSisLocked(trsContract, repo));

        // Contract should stay in 'locked' state even while live (started).
        startTRScontract(trsContract, repo);
        assertTrue(TRSisLocked(trsContract, repo));
    }

    /**
     * Tests we can query whether the contract is started (is live) or not.
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
     * Tests we can query the address of the contract's owner correctly.
     */
    @Test
    public void testOwnerQuery() {
        int periods = 4;
        int t0special = 1;
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, periods, t0special);
        Address owner = TRSwhoIsOwner(trsContract, repo);
        assertEquals(deployer, owner);
    }

    /**
     * Tests we can query the address of the contract's new owner correctly.
     */
    @Test
    public void testNewOwnerQuery() {
        int periods = 4;
        int t0special = 1;
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, periods, t0special);

        // First try query when the new owner has not yet been set.
        assertEquals(Address.ZERO_ADDRESS(), TRSwhoIsNewOwner(trsContract, repo));

        // Second try the query when the new owner has been set; assert newOwner is not owner.
        Address newOwner = makeAccount(repo, DEFAULT_BALANCE);
        TRSproposeNewOwner(trsContract, repo, newOwner);
        assertEquals(newOwner, TRSwhoIsNewOwner(trsContract, repo));
        assertEquals(deployer, TRSwhoIsOwner(trsContract, repo));
        lockTRScontract(trsContract, repo, newOwner);
        assertFalse(TRSisLocked(trsContract, repo));

        // Third try the query after the new owner has accepted their role.
        Address imposter = makeAccount(repo, DEFAULT_BALANCE);
        TRSacceptOwnership(trsContract, repo, imposter);
        assertEquals(deployer, TRSwhoIsOwner(trsContract, repo));

        TRSacceptOwnership(trsContract, repo, newOwner);
        deployer = newOwner;
        assertEquals(newOwner, TRSwhoIsOwner(trsContract, repo));
        assertEquals(Address.ZERO_ADDRESS(), TRSwhoIsNewOwner(trsContract, repo));
        lockTRScontract(trsContract, repo, newOwner);
        assertTrue(TRSisLocked(trsContract, repo));
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
        List<BigInteger> balances = getRandomBalances(numAccounts);
        List<Address> accounts = makeAccounts(repo, DEFAULT_BALANCE, numAccounts);
        depositIntoTRScontract(trsContract, repo, accounts, balances);

        BigInteger expectedFacevalue = sumOf(balances);
        assertEquals(expectedFacevalue, TRSgetTotalFacevalue(trsContract, repo));

        // Once account is locked we expect that no more deposits can be made.
        lockTRScontract(trsContract, repo);
        assertTrue(TRSisLocked(trsContract, repo));
        numAccounts = 200;
        balances = getRandomBalances(numAccounts);
        accounts = makeAccounts(repo, DEFAULT_BALANCE, numAccounts);
        depositIntoTRScontractWillFail(trsContract, repo, accounts, balances);
        assertEquals(expectedFacevalue, TRSgetTotalFacevalue(trsContract, repo));

        // Now we start the contract and still expect no deposits can be made.
        startTRScontract(trsContract, repo);
        assertTrue(TRSisStarted(trsContract, repo));
        accounts = makeAccounts(repo, DEFAULT_BALANCE, numAccounts);
        depositIntoTRScontractWillFail(trsContract, repo, accounts, balances);
        assertEquals(expectedFacevalue, TRSgetTotalFacevalue(trsContract, repo));
    }

    /**
     * Tests the withdrawTo functionality on a contract with numerous depositors such that each
     * depositor makes their first withdrawal in the final period of the contract.
     *
     * The contract only contains the amounts deposited by the depositors and therefore we expect
     * each depositor to claim the same amount X of coins that they initially deposited into the
     * contract.
     *
     * We also verify the total amount of funds in the contract before withdrawals and ensure that
     * this same amount of coins was distributed to the withdrawers and that once all withdrawals
     * are over the contract has zero remaining funds. Also verify that multiple withdrawals do not
     * interfere with these results.
     */
    @Test
    public void testWithdrawalInLastPeriodOfContract() {
        BigInteger bonusDeposits = BigInteger.ZERO;
        int numDepositors = 1_000;
        int periods = RandomUtils.nextInt(4, 60);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testWithdrawalInLastPeriodOfContract using periods value: " + periods);
        System.out.println("testWithdrawalInLastPeriodOfContract using t0special value: " + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // After this call we know all accounts have deposited all their funds into trsContract.
        ObjectHolder holder = setupTRScontractWithDeposits(repo, periods, t0special, numDepositors,
            bonusDeposits);
        Address trsContract = (Address) holder.grabObjectAtPosition(0);
        List<Address> accounts = (List<Address>) holder.grabObjectAtPosition(1);
        List<BigInteger> balances = (List<BigInteger>) holder.grabObjectAtPosition(2);
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);
        assertEquals(totalFunds, TRSgetRemainder(trsContract, repo));

        // Verify each account has correct balance in contract and that contract has correct sum.
        verifyAccountsInContract(trsContract, repo, accounts, balances, bonusDeposits);
        assertAllAccountsHaveZeroBalance(repo, accounts);

        // Move into final period and make withdrawals.
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        addBlock(blockchain.getBestBlock().getTimestamp() + (periods * periodInterval));

        // Only the first withdrawal attempt should succeed.
        BigInteger sum = BigInteger.ZERO;
        int i = 0;
        for (Address account : accounts) {
            assertTrue(TRSwithdrawFundsFor(trsContract, repo, account));
            assertFalse(TRSwithdrawFundsFor(trsContract, repo, account));
            BigInteger totalOwed = computeTotalOwed(trsContract, repo, account);
            assertEquals(balances.get(i), totalOwed);
            assertEquals(totalOwed, repo.getBalance(account));
            sum = sum.add(totalOwed);
            i++;
        }

        // Check that the total amount of funds the contract contained was distributed and the
        // contract has no remaining funds in it.
        assertEquals(totalFunds, sum);
        assertEquals(BigInteger.ZERO, TRSgetRemainder(trsContract, repo));
    }

    /**
     * Tests the withdrawTo functionality on a contract with numerous depositors such that each
     * depositor makes their first withdrawal in the final period of the contract and this contract
     * has bonus deposits in it.
     *
     * Since the contract contains bonus deposits we expect that for each depositor who has deposited
     * X coins into the contract, that depositor will withdraw at least X coins back.
     *
     * We verify the total amount of funds in the contract before withdrawals is the sum of deposits
     * plus the bonus and that this amount, within a small error tolerance, is distributed back to
     * the depositors. Also verify that multiple withdrawals do not interfere with the results and
     * that the contract has enough funds to pay out everyone.
     */
    @Test
    public void testWithdrawalInLastPeriodOfContractExtraDeposits() {
        BigInteger bonusDeposits = BigInteger.valueOf(RandomUtils.nextInt(1_000, 100_000));
        int numDepositors = 1_000;
        int periods = RandomUtils.nextInt(4, 60);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testWithdrawalInLastPeriodOfContractExtraDeposits using bonusDeposits:"
            + bonusDeposits);
        System.out.println("testWithdrawalInLastPeriodOfContractExtraDeposits using periods value:"
            + periods);
        System.out.println("testWithdrawalInLastPeriodOfContractExtraDeposits using t0special value:"
            + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // After this call we know all accounts have deposited all their funds into trsContract.
        ObjectHolder holder = setupTRScontractWithDeposits(repo, periods, t0special, numDepositors,
            bonusDeposits);
        Address trsContract = (Address) holder.grabObjectAtPosition(0);
        List<Address> accounts = (List<Address>) holder.grabObjectAtPosition(1);
        List<BigInteger> balances = (List<BigInteger>) holder.grabObjectAtPosition(2);
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);
        assertEquals(sumOf(balances).add(bonusDeposits), totalFunds);
        assertEquals(totalFunds, TRSgetRemainder(trsContract, repo));

        // Verify each account has correct balance in contract and that contract has correct sum.
        verifyAccountsInContract(trsContract, repo, accounts, balances, bonusDeposits);
        assertAllAccountsHaveZeroBalance(repo, accounts);

        // Move into final period and make withdrawals.
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        addBlock(blockchain.getBestBlock().getTimestamp() + (periods * periodInterval));

        // Only the first withdrawal attempt should succeed.
        BigInteger sum = BigInteger.ZERO;
        int i = 0;
        for (Address account : accounts) {
            assertTrue(TRSwithdrawFundsFor(trsContract, repo, account));
            assertFalse(TRSwithdrawFundsFor(trsContract, repo, account));
            BigInteger totalOwed = computeTotalOwed(trsContract, repo, account);
            assertTrue(balances.get(i).compareTo(totalOwed) <= 0);
            assertEquals(totalOwed, repo.getBalance(account));
            sum = sum.add(totalOwed);
            i++;
        }

        // Check that the contract has enough funds in it to make all of the withdrawals and that
        // the contract has the appropriate remainder.
        BigInteger remainder = TRSgetRemainder(trsContract, repo);
        assertTrue(sum.compareTo(totalFunds) <= 0);
        assertEquals(totalFunds.subtract(sum), remainder);

        // I believe that the remainder should be strictly less than the number of depositors in
        // all cases... I may be wrong here...
        assertTrue(remainder.compareTo(BigInteger.valueOf(numDepositors)) < 0);
    }

    /**
     * Tests the withdrawTo functionality on a contract with numerous depositors such that each
     * depositor makes multiple withdraw requests during every period of the contract right into its
     * final period.
     *
     * We expect that none of the extra withdrawal attempts will affect the results and that after
     * all of the withdrawals each account will receive back the original X coins it had deposited
     * into the contract.
     *
     * We check that each account does in fact recieve this amount and that the contract has no
     * remaining funds after all of the withdrawals finish.
     */
    @Test
    public void testWithdrawalsOverFullContractLifetime() {
        BigInteger bonusDeposits = BigInteger.ZERO;
        int numDepositors = 1_000;
        int periods = RandomUtils.nextInt(4, 60);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testWithdrawalsOverFullContractLifetime using periods value: "
            + periods);
        System.out.println("testWithdrawalsOverFullContractLifetime using t0special value: "
            + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // After this call we know all accounts have deposited all their funds into trsContract.
        ObjectHolder holder = setupTRScontractWithDeposits(repo, periods, t0special, numDepositors,
            bonusDeposits);
        Address trsContract = (Address) holder.grabObjectAtPosition(0);
        List<Address> accounts = (List<Address>) holder.grabObjectAtPosition(1);
        List<BigInteger> balances = (List<BigInteger>) holder.grabObjectAtPosition(2);
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);
        assertEquals(totalFunds, TRSgetRemainder(trsContract, repo));

        // Verify each account has correct balance in contract and that contract has correct sum.
        verifyAccountsInContract(trsContract, repo, accounts, balances, bonusDeposits);
        assertAllAccountsHaveZeroBalance(repo, accounts);

        // Move through each period and make excessive withdrawals in each.
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        makeExcessWithdrawalsInAllPeriods(trsContract, repo, periods, periodInterval, accounts);

        // Check that each account has withdrawn its original deposits and that the contract has
        // no remainder left.
        int i = 0;
        for (Address account : accounts) {
            assertEquals(balances.get(i), repo.getBalance(account));
            i++;
        }
        assertEquals(BigInteger.ZERO, TRSgetRemainder(trsContract, repo));
    }

    /**
     * Tests the withdrawTo functionality on a contract with numerous depositors such that each
     * depositor makes multiple withdraw requests during every period of the contract right into its
     * final period.
     *
     * We expect that none of the extra withdrawal attempts will affect the results and that after
     * all of the withdrawals each account will receive back the original X coins it had deposited
     * into the contract plus potentially some bonus funds.
     *
     * We check that each account does in fact recieve this amount at least and that the contract's
     * remaining funds are within the expected bounds and that the contract has sufficient funds to
     * pay out all accounts.
     */
    @Test
    public void testWithdrawalsOverFullContractLifetimeWithExtraDeposits() {
        BigInteger bonusDeposits = BigInteger.valueOf(RandomUtils.nextInt(1_000, 100_000));
        int numDepositors = 1_000;
        int periods = RandomUtils.nextInt(4, 60);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testWithdrawalsOverFullContractLifetimeWithExtraDeposits using bonus"
            + "Deposits: " + bonusDeposits);
        System.out.println("testWithdrawalsOverFullContractLifetimeWithExtraDeposits using periods "
            + "value: " + periods);
        System.out.println("testWithdrawalsOverFullContractLifetimeWithExtraDeposits using "
            + "t0special value: " + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // After this call we know all accounts have deposited all their funds into trsContract.
        ObjectHolder holder = setupTRScontractWithDeposits(repo, periods, t0special, numDepositors,
            bonusDeposits);
        Address trsContract = (Address) holder.grabObjectAtPosition(0);
        List<Address> accounts = (List<Address>) holder.grabObjectAtPosition(1);
        List<BigInteger> balances = (List<BigInteger>) holder.grabObjectAtPosition(2);
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);
        assertEquals(sumOf(balances).add(bonusDeposits), totalFunds);
        assertEquals(totalFunds, TRSgetRemainder(trsContract, repo));

        // Verify each account has correct balance in contract and that contract has correct sum.
        verifyAccountsInContract(trsContract, repo, accounts, balances, bonusDeposits);
        assertAllAccountsHaveZeroBalance(repo, accounts);

        // Move through each period and make excessive withdrawals in each.
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        makeExcessWithdrawalsInAllPeriods(trsContract, repo, periods, periodInterval, accounts);

        // Check that each account has withdrawn its original deposits and that the contract has
        // no remainder left.
        BigInteger sum = BigInteger.ZERO;
        int i = 0;
        for (Address account : accounts) {
            BigInteger actualBalance = repo.getBalance(account);
            sum = sum.add(actualBalance);
            assertTrue(balances.get(i).compareTo(actualBalance) <= 0);
            i++;
        }

        // Check that the contract has enough funds in it to make all of the withdrawals and that
        // the contract has the appropriate remainder.
        BigInteger remainder = TRSgetRemainder(trsContract, repo);
        assertTrue(sum.compareTo(totalFunds) <= 0);
        assertEquals(totalFunds.subtract(sum), remainder);

        // I believe that the remainder should be strictly less than the number of depositors in
        // all cases... I may be wrong here...
        assertTrue(remainder.compareTo(BigInteger.valueOf(numDepositors)) < 0);
    }

    /**
     * Tests that the fraction of funds each account in the TRS contract is eligible to withdraw
     * in each period of the contract is equal to the expected amount and that in the final period
     * the fraction of funds the accounts can withdraw is equal to 1 -- so they are eligible to
     * withdraw all their funds.
     */
    @Test
    public void testWithdrawalFractionPerEachPeriod() {
        BigInteger bonusDeposits = BigInteger.ZERO;
        int numDepositors = 200;
        int periods = RandomUtils.nextInt(4, 60);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testWithdrawalFractionPerEachPeriod using periods value: " + periods);
        System.out.println("testWithdrawalFractionPerEachPeriod using t0special value: " + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // After this call we know all accounts have deposited all their funds into trsContract.
        ObjectHolder holder = setupTRScontractWithDeposits(repo, periods, t0special, numDepositors,
            bonusDeposits);
        Address trsContract = (Address) holder.grabObjectAtPosition(0);
        List<Address> accounts = (List<Address>) holder.grabObjectAtPosition(1);
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);
        assertEquals(totalFunds, TRSgetRemainder(trsContract, repo));

        // We move through each period of the contract and verify that each account is eligible to
        // withdraw the expected amounts.
        BigInteger precision = TRSgetPrecision(trsContract, repo);
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        for (Address account : accounts) {
            // Ensure we have enough balance to cover the transaction costs.
            repo.addBalance(account, DEFAULT_BALANCE);

            long periodStartTime = TRSgetStartTime(trsContract, repo);
            for (int i = 1; i <= periods; i++) {
                BigInteger fraction = TRSfractionEligibleToWithdraw(trsContract, repo, account, periodStartTime);
                BigInteger expectedFraction = fraction(t0special, i, periods, precision);
                assertEquals(expectedFraction, fraction);
                periodStartTime += periodInterval;

                if (i == periods) {
                    // Verify that in the final period the fraction to withdraw is 1 (ie. 100%).
                    assertEquals(correctToPrecision(BigDecimal.ONE, precision), fraction);
                }
            }
        }
    }

    /**
     * Tests that the fraction of funds each account in the TRS contract is eligible to withdraw
     * in each period of the contract is equal to the expected amount and that in the final period
     * the fraction of funds the accounts can withdraw is equal to 1 -- so they are eligible to
     * withdraw all their funds.
     *
     * This method tests the same functionality as the above method except there are bonus funds in
     * this contract.
     */
    @Test
    public void testWithdrawalFractionPerEachPeriodWithExtraDeposits() {
        BigInteger bonusDeposits = BigInteger.valueOf(RandomUtils.nextInt(1_000, 100_000));
        int numDepositors = 200;
        int periods = RandomUtils.nextInt(4, 60);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testWithdrawalFractionPerEachPeriodWithExtraDeposits using "
            + " bonusDeposits: " + bonusDeposits);
        System.out.println("testWithdrawalFractionPerEachPeriodWithExtraDeposits using periods "
            + " value: " + periods);
        System.out.println("testWithdrawalFractionPerEachPeriodWithExtraDeposits using t0special "
            + " value: " + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // After this call we know all accounts have deposited all their funds into trsContract.
        ObjectHolder holder = setupTRScontractWithDeposits(repo, periods, t0special, numDepositors,
            bonusDeposits);
        Address trsContract = (Address) holder.grabObjectAtPosition(0);
        List<Address> accounts = (List<Address>) holder.grabObjectAtPosition(1);
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);
        assertEquals(totalFunds, TRSgetRemainder(trsContract, repo));

        // We move through each period of the contract and verify that each account is eligible to
        // withdraw the expected amounts.
        BigInteger precision = TRSgetPrecision(trsContract, repo);
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        for (Address account : accounts) {
            // Ensure we have enough balance to cover the transaction costs.
            repo.addBalance(account, DEFAULT_BALANCE);

            long periodStartTime = TRSgetStartTime(trsContract, repo);
            for (int i = 1; i <= periods; i++) {
                BigInteger fraction = TRSfractionEligibleToWithdraw(trsContract, repo, account, periodStartTime);
                BigInteger expectedFraction = fraction(t0special, i, periods, precision);
                assertEquals(expectedFraction, fraction);
                periodStartTime += periodInterval;

                if (i == periods) {
                    // Verify that in the final period the fraction to withdraw is 1 (ie. 100%).
                    assertEquals(correctToPrecision(BigDecimal.ONE, precision), fraction);
                }
            }
        }
    }

    /**
     * Tests that a call to withdrawTo during each period of the contract allows the caller to
     * withdraw the appropriate fraction of funds they are entitled to.
     *
     * Since this call actually withdraws the amount as well, we use periods amount of accounts that
     * each have deposited the same amount and have each one withdraw in a different period in order
     * to see how each period's withdrawal works independent of the previous periods.
     */
    @Test
    public void testWithdrawalAmountsPerEachPeriod() {
        BigInteger bonusDeposits = BigInteger.ZERO;
        int periods = RandomUtils.nextInt(1, 100);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testWithdrawalAmountsPerEachPeriod using periods value: " + periods);
        System.out.println("testWithdrawalAmountsPerEachPeriod using t0special value: " + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // After this call we know all accounts have deposited all their funds into trsContract.
        BigInteger depositAmount = BigInteger.valueOf(RandomUtils.nextInt(20_000, 250_000));
        System.out.println("testWithdrawalAmountsPerEachPeriod using depositAmount: " + depositAmount);
        List<Address> accounts = setupTRScontractFixedDepositAmounts(repo, periods, t0special,
            periods, depositAmount, bonusDeposits);
        Address trsContract = accounts.remove(0);
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);
        assertEquals(totalFunds, TRSgetRemainder(trsContract, repo));

        // We move through each period of the contract and verify the withdrawal amount each time.
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        long periodStartTime = TRSgetStartTime(trsContract, repo);
        for (int i = 1; i <= periods; i++) {
            // Grab account and give it some balance for the transaction cost.
            Address account = accounts.get(i - 1);
            repo.addBalance(account, DEFAULT_BALANCE);

            BigInteger expectedWithdraw = computeExpectedWithdrawalAtPeriod(trsContract, repo,
                account, t0special, i, periods);

            wipeBalance(repo, account);
            assertTrue(TRSwithdrawFundsFor(trsContract, repo, account));
            BigInteger withdraw = repo.getBalance(account);
            assertEquals(expectedWithdraw, withdraw);

            // move to next period.
            periodStartTime += periodInterval;
            addBlock(periodStartTime);
        }
    }

    @Test
    public void testWithdrawalAmountsPerEachPeriodWithExtraDeposits() {
        BigInteger bonusDeposits = BigInteger.valueOf(RandomUtils.nextInt(1_000, 100_000));
        int periods = RandomUtils.nextInt(1, 100);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testWithdrawalAmountsPerEachPeriodWithExtraDeposits using bonusDeposits: "
            + bonusDeposits);
        System.out.println("testWithdrawalAmountsPerEachPeriodWithExtraDeposits using periods value: "
            + periods);
        System.out.println("testWithdrawalAmountsPerEachPeriodWithExtraDeposits using t0special value: "
            + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // After this call we know all accounts have deposited all their funds into trsContract.
        BigInteger depositAmount = BigInteger.valueOf(RandomUtils.nextInt(20_000, 250_000));
        System.out.println("testWithdrawalAmountsPerEachPeriodWithExtraDeposits using depositAmount: "
            + depositAmount);
        List<Address> accounts = setupTRScontractFixedDepositAmounts(repo, periods, t0special,
            periods, depositAmount, bonusDeposits);
        Address trsContract = accounts.remove(0);
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);
        assertEquals(totalFunds, TRSgetRemainder(trsContract, repo));

        // We move through each period of the contract and verify the withdrawal amount each time.
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        long periodStartTime = TRSgetStartTime(trsContract, repo);
        for (int i = 1; i <= periods; i++) {
            // Grab account and give it some balance for the transaction cost.
            Address account = accounts.get(i - 1);
            repo.addBalance(account, DEFAULT_BALANCE);

            BigInteger expectedWithdraw = computeExpectedWithdrawalAtPeriod(trsContract, repo,
                account, t0special, i, periods);

            wipeBalance(repo, account);
            assertTrue(TRSwithdrawFundsFor(trsContract, repo, account));
            BigInteger withdraw = repo.getBalance(account);
            assertEquals(expectedWithdraw, withdraw);

            // move to next period.
            periodStartTime += periodInterval;
            addBlock(periodStartTime);
        }
    }

    /**
     * Tests that a call to withdrawTo on an account that has no deposits in the contract does not
     * cause any coins from the contract to be withdrawn.
     */
    @Test
    public void testWithdrawForAccountThatHasNoDepositsInContract() {
        BigInteger bonusDeposits = BigInteger.valueOf(RandomUtils.nextInt(1_000, 100_000));
        int numDepositors = 200;
        int periods = RandomUtils.nextInt(4, 60);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testWithdrawForAccountThatHasNoDepositsInContract using "
            + " bonusDeposits: " + bonusDeposits);
        System.out.println("testWithdrawForAccountThatHasNoDepositsInContract using periods "
            + " value: " + periods);
        System.out.println("testWithdrawForAccountThatHasNoDepositsInContract using t0special "
            + " value: " + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // After this call we know all accounts have deposited all their funds into trsContract.
        ObjectHolder holder = setupTRScontractWithDeposits(repo, periods, t0special, numDepositors,
            bonusDeposits);
        Address trsContract = (Address) holder.grabObjectAtPosition(0);
        List<Address> accounts = (List<Address>) holder.grabObjectAtPosition(1);

        // Make sure, in a very paranoid manner, we have an account that is not a depositor.
        Address stranger = makeAccount(repo, BigInteger.ZERO);
        while (accounts.contains(stranger)) {
            stranger = makeAccount(repo, BigInteger.ZERO);
        }

        // Check that no funds leave the contract and no funds enter the stranger's account.
        BigInteger remainder = TRSgetRemainder(trsContract, repo);
        assertEquals(remainder, TRSgetTotalFunds(trsContract, repo));
        assertFalse(TRSwithdrawFundsFor(trsContract, repo, stranger));
        assertEquals(BigInteger.ZERO, repo.getBalance(stranger));
        assertEquals(remainder, TRSgetRemainder(trsContract, repo));
    }

    @Test
    public void testWithdrawBeforeLive() {
        BigInteger bonusDeposits = BigInteger.valueOf(RandomUtils.nextInt(1_000, 100_000));
        int numDepositors = 1;
        int periods = RandomUtils.nextInt(4, 60);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testWithdrawForAccountThatHasNoDepositsInContract using "
            + " bonusDeposits: " + bonusDeposits);
        System.out.println("testWithdrawForAccountThatHasNoDepositsInContract using periods "
            + " value: " + periods);
        System.out.println("testWithdrawForAccountThatHasNoDepositsInContract using t0special "
            + " value: " + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, periods, t0special);

        // Try to withdraw before contract is locked.
        List<BigInteger> balances = getRandomBalances(numDepositors);
        List<Address> accounts = makeAccounts(repo, DEFAULT_BALANCE, balances.size());
        depositIntoTRScontract(trsContract, repo, accounts, balances);

        Address account = accounts.get(0);
        BigInteger balance = repo.getBalance(account);

        assertEquals(balance, repo.getBalance(account));
        TRSwithdrawFundsForWillFail(trsContract, repo, account);
        assertEquals(balance, repo.getBalance(account));

        // Try to withdraw after locked but not yet live.
        lockTRScontract(trsContract, repo);
        TRSwithdrawFundsForWillFail(trsContract, repo, account);
        assertEquals(balance, repo.getBalance(account));
    }

    /**
     * Tests calling bulkWithdraw in the final period of a TRS contract and checks that each address
     * receives all of the original funds it deposited back and that the contract has zero funds
     * remaining after the withdrawal.
     *
     * Also verifies that subsequent withdrawals do not interfere with the results.
     */
    @Test
    public void testBulkWithdrawalInFinalPeriod() {
        BigInteger bonusDeposits = BigInteger.ZERO;
        int numDepositors = 25;
        int periods = RandomUtils.nextInt(4, 60);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testBulkWithdrawal using periods value: " + periods);
        System.out.println("testBulkWithdrawal using t0special value: " + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // After this call we know all accounts have deposited all their funds into trsContract.
        ObjectHolder holder = setupTRScontractWithDeposits(repo, periods, t0special, numDepositors,
            bonusDeposits);
        Address trsContract = (Address) holder.grabObjectAtPosition(0);
        List<Address> accounts = (List<Address>) holder.grabObjectAtPosition(1);
        List<BigInteger> balances = (List<BigInteger>) holder.grabObjectAtPosition(2);
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);
        assertEquals(totalFunds, TRSgetRemainder(trsContract, repo));

        // Move into the final period of the contract and make the bulk withdrawal.
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        long startTime = TRSgetStartTime(trsContract, repo);
        addBlock(startTime + (periods * periodInterval));

        // We make 2 calls to ensure the second doesn't interfere with the first.
        NRG = Constants.NRG_TRANSACTION_MAX;
        TRSbulkWithdrawal(trsContract, repo, accounts);
        TRSbulkWithdrawal(trsContract, repo, accounts);

        BigInteger sum = BigInteger.ZERO;
        int i = 0;
        for (Address account : accounts) {
            BigInteger balance = repo.getBalance(account);
            sum = sum.add(balance);
            assertEquals(balances.get(i), balance);
            i++;
        }

        assertEquals(totalFunds, sum);
        assertEquals(BigInteger.ZERO, TRSgetRemainder(trsContract, repo));
    }

    /**
     * Tests calling bulkWithdraw in the final period of a TRS contract that has some bonus funds in
     * it and checks that each address receives at least all of the original funds (receives the
     * expected amount) it deposited back.
     *
     * Also verifies that subsequent withdrawals do not interfere with the results, and that the
     * remaining funds in the contract are less than the number of accounts in the contract.
     */
    @Test
    public void testBulkWithdrawalInFinalPeriodWithExtraFunds() {
        BigInteger bonusDeposits = BigInteger.valueOf(RandomUtils.nextInt(1_000, 100_000));
        int numDepositors = 25;
        int periods = RandomUtils.nextInt(4, 60);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testBulkWithdrawalInFinalPeriodWithExtraFunds using bonusDeposits: "
            + bonusDeposits);
        System.out.println("testBulkWithdrawalInFinalPeriodWithExtraFunds using periods value: "
            + periods);
        System.out.println("testBulkWithdrawalInFinalPeriodWithExtraFunds using t0special value: "
            + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // After this call we know all accounts have deposited all their funds into trsContract.
        ObjectHolder holder = setupTRScontractWithDeposits(repo, periods, t0special, numDepositors,
            bonusDeposits);
        Address trsContract = (Address) holder.grabObjectAtPosition(0);
        List<Address> accounts = (List<Address>) holder.grabObjectAtPosition(1);
        List<BigInteger> balances = (List<BigInteger>) holder.grabObjectAtPosition(2);
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);
        assertEquals(totalFunds, TRSgetRemainder(trsContract, repo));

        // Move into the final period of the contract and make the bulk withdrawal.
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        long startTime = TRSgetStartTime(trsContract, repo);
        addBlock(startTime + (periods * periodInterval));

        NRG = Constants.NRG_TRANSACTION_MAX;
        TRSbulkWithdrawal(trsContract, repo, accounts);

        BigInteger sum = BigInteger.ZERO;
        int i = 0;
        for (Address account : accounts) {
            BigInteger balance = repo.getBalance(account);
            sum = sum.add(balance);
            assertTrue(balances.get(i).compareTo(balance) <= 0);
            i++;
        }

        // Check that the contract has enough funds in it to make all of the withdrawals and that
        // the contract has the appropriate remainder.
        BigInteger remainder = TRSgetRemainder(trsContract, repo);
        assertTrue(sum.compareTo(totalFunds) <= 0);
        assertEquals(totalFunds.subtract(sum), remainder);

        // I believe that the remainder should be strictly less than the number of depositors in
        // all cases... I may be wrong here...
        assertTrue(remainder.compareTo(BigInteger.valueOf(numDepositors)) < 0);
    }

    /**
     * Tests the bulkWithdraw functionality on a contract with numerous depositors such that multiple
     * bulk withdrawal requests are made during every period of the contract right into its final
     * period.
     *
     * We expect that none of the extra withdrawal attempts will affect the results and that after
     * all of the withdrawals each account will receive back the original X coins it had deposited
     * into the contract.
     *
     * We check that each account does in fact recieve this amount and that the contract has zero
     * remaining funds in it and that the contract had sufficient funds in the first place to pay
     * out all accounts.
     */
    @Test
    public void testBulkWithdrawalOverContractLifetime() {
        BigInteger bonusDeposits = BigInteger.ZERO;
        int numDepositors = 25;
        int periods = RandomUtils.nextInt(4, 60);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testBulkWithdrawalOverContractLifetime using periods value: "
            + periods);
        System.out.println("testBulkWithdrawalOverContractLifetime using t0special value: "
            + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // After this call we know all accounts have deposited all their funds into trsContract.
        ObjectHolder holder = setupTRScontractWithDeposits(repo, periods, t0special, numDepositors,
            bonusDeposits);
        Address trsContract = (Address) holder.grabObjectAtPosition(0);
        List<Address> accounts = (List<Address>) holder.grabObjectAtPosition(1);
        List<BigInteger> balances = (List<BigInteger>) holder.grabObjectAtPosition(2);
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);
        assertEquals(totalFunds, TRSgetRemainder(trsContract, repo));

        // Verify each account has correct balance in contract and that contract has correct sum.
        verifyAccountsInContract(trsContract, repo, accounts, balances, bonusDeposits);
        assertAllAccountsHaveZeroBalance(repo, accounts);

        // Move through each period and make excessive withdrawals in each.
        NRG = Constants.NRG_TRANSACTION_MAX;
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        makeExcessBulkWithdrawalsInAllPeriods(trsContract, repo, periods, periodInterval, accounts);

        // Check that each account has withdrawn its original deposits and that the contract has
        // no remainder left.
        int i = 0;
        for (Address account : accounts) {
            assertEquals(balances.get(i), repo.getBalance(account));
            i++;
        }
        assertEquals(BigInteger.ZERO, TRSgetRemainder(trsContract, repo));
    }

    /**
     * Tests the bulkWithdraw functionality on a contract with numerous depositors such that multiple
     * bulk withdrawal requests are made during every period of the contract right into its final
     * period.
     *
     * We expect that none of the extra withdrawal attempts will affect the results and that after
     * all of the withdrawals each account will receive back the original X coins it had deposited
     * into the contract plus potentially some bonus funds.
     *
     * We check that each account does in fact recieve this amount at least and that the contract's
     * remaining funds are within the expected bounds and that the contract has sufficient funds to
     * pay out all accounts.
     */
    @Test
    public void testBulkWithdrawalOverContractLifetimeWithExtraFunds() {
        BigInteger bonusDeposits = BigInteger.valueOf(RandomUtils.nextInt(1_000, 100_000));
        int numDepositors = 25;
        int periods = RandomUtils.nextInt(4, 60);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testBulkWithdrawalOverContractLifetimeWithExtraFunds using bonusDeposits: "
            + bonusDeposits);
        System.out.println("testBulkWithdrawalOverContractLifetimeWithExtraFunds using periods value: "
            + periods);
        System.out.println("testBulkWithdrawalOverContractLifetimeWithExtraFunds using t0special value: "
            + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // After this call we know all accounts have deposited all their funds into trsContract.
        ObjectHolder holder = setupTRScontractWithDeposits(repo, periods, t0special, numDepositors,
            bonusDeposits);
        Address trsContract = (Address) holder.grabObjectAtPosition(0);
        List<Address> accounts = (List<Address>) holder.grabObjectAtPosition(1);
        List<BigInteger> balances = (List<BigInteger>) holder.grabObjectAtPosition(2);
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);
        assertEquals(totalFunds, TRSgetRemainder(trsContract, repo));

        // Verify each account has correct balance in contract and that contract has correct sum.
        verifyAccountsInContract(trsContract, repo, accounts, balances, bonusDeposits);
        assertAllAccountsHaveZeroBalance(repo, accounts);

        // Move through each period and make excessive withdrawals in each.
        NRG = Constants.NRG_TRANSACTION_MAX;
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        makeExcessBulkWithdrawalsInAllPeriods(trsContract, repo, periods, periodInterval, accounts);

        // Check that each account has withdrawn its original deposits at least.
        BigInteger sum = BigInteger.ZERO;
        int i = 0;
        for (Address account : accounts) {
            BigInteger balance = repo.getBalance(account);
            sum = sum.add(balance);
            assertTrue(balances.get(i).compareTo(balance) <= 0);
            i++;
        }

        // Check that the contract has enough funds in it to make all of the withdrawals and that
        // the contract has the appropriate remainder.
        BigInteger remainder = TRSgetRemainder(trsContract, repo);
        assertTrue(sum.compareTo(totalFunds) <= 0);
        assertEquals(totalFunds.subtract(sum), remainder);

        // I believe that the remainder should be strictly less than the number of depositors in
        // all cases... I may be wrong here...
        assertTrue(remainder.compareTo(BigInteger.valueOf(numDepositors)) < 0);
    }

    /**
     * Tests that a call to bulkWithdraw during each period of the contract allows the caller to
     * withdraw the appropriate fraction of funds they are entitled to.
     *
     * Since this call actually withdraws the amount as well, we use periods * L accounts such that
     * if we break these up into L lists of size periods, the i'th account in all of the L lists
     * have the deposited the same amount. We can think of there being L copies of each of the
     * periods number of distinct accounts.
     *
     * Now we perform a bulk withdraw on each of the L lists periods time, once per period, so that
     * we can capture the amount that the i'th account is able to withdraw in each of the periods
     * independent of the prior withdrawals.
     */
    @Test
    public void testBulkWithdrawalPerEachPeriod() {
        BigInteger bonusDeposits = BigInteger.ZERO;
        int numDepositors = 25;
        int periods = RandomUtils.nextInt(1, 100);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testBulkWithdrawalPerEachPeriod using periods value: " + periods);
        System.out.println("testBulkWithdrawalPerEachPeriod using t0special value: " + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // we set: L = numDepositors    (see documentation above if this makes no sense.)
        List<List<Address>> accounts = new ArrayList<>(periods);
        List<BigInteger> balances = getRandomBalances(numDepositors);
        for (int i = 0; i < periods; i++) {
            accounts.add(makeAccounts(repo, balances));
        }

        // Set up the contract with all of the deposits; give each account extra balance to ensure
        // they can cover the tx costs.
        List<Address> allAccounts = flatten(accounts);
        List<BigInteger> allBalances = replicate(balances, periods);
        assertEquals(numDepositors * periods, allAccounts.size());
        assertEquals(allAccounts.size(), allBalances.size());
        giveBalanceTo(repo, allAccounts, DEFAULT_BALANCE);
        Address trsContract = TRSdepositAndMakeLive(repo, periods, t0special, allAccounts,
            allBalances, bonusDeposits);
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);
        assertEquals(totalFunds, TRSgetRemainder(trsContract, repo));

        // We move through each period of the contract and verify the withdrawal amount each time.
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        long periodStartTime = TRSgetStartTime(trsContract, repo);
        for (int i = 1; i <= periods; i++) {
            // Grab account and give it some balance for the transaction cost.
            List<Address> accountsThisRound = accounts.get(i - 1);
            giveAllAccountsBalance(repo, accountsThisRound, DEFAULT_BALANCE);

            List<BigInteger> expectedWithdraw = computeExpectedWithdrawalsAtPeriod(trsContract, repo,
                accountsThisRound, t0special, i, periods);

            NRG = Constants.NRG_TRANSACTION_MAX;
            wipeAllBalances(repo, accountsThisRound);
            TRSbulkWithdrawal(trsContract, repo, accountsThisRound);
            List<BigInteger> withdrawals = getBalancesOf(repo, accountsThisRound);
            assertEquals(expectedWithdraw, withdrawals);

            // move to next period.
            periodStartTime += periodInterval;
            addBlock(periodStartTime);
        }
    }

    /**
     * Tests that a call to bulkWithdraw during each period of the contract allows the caller to
     * withdraw the appropriate fraction of funds they are entitled to when the contract has
     * bonus deposits in it.
     *
     * Since this call actually withdraws the amount as well, we use periods * L accounts such that
     * if we break these up into L lists of size periods, the i'th account in all of the L lists
     * have the deposited the same amount. We can think of there being L copies of each of the
     * periods number of distinct accounts.
     *
     * Now we perform a bulk withdraw on each of the L lists periods time, once per period, so that
     * we can capture the amount that the i'th account is able to withdraw in each of the periods
     * independent of the prior withdrawals.
     */
    @Test
    public void testBulkWithdrawalPerEachPeriodWithExtraDeposits() {
        BigInteger bonusDeposits = BigInteger.valueOf(RandomUtils.nextInt(1_000, 100_000));
        int numDepositors = 25;
        int periods = RandomUtils.nextInt(1, 100);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testBulkWithdrawalPerEachPeriodWithExtraDeposits using bonusDeposits: "
            + bonusDeposits);
        System.out.println("testBulkWithdrawalPerEachPeriodWithExtraDeposits using periods value: "
            + periods);
        System.out.println("testBulkWithdrawalPerEachPeriodWithExtraDeposits using t0special value: "
            + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // we set: L = numDepositors    (see documentation above if this makes no sense.)
        List<List<Address>> accounts = new ArrayList<>(periods);
        List<BigInteger> balances = getRandomBalances(numDepositors);
        for (int i = 0; i < periods; i++) {
            accounts.add(makeAccounts(repo, balances));
        }

        // Set up the contract with all of the deposits; give each account extra balance to ensure
        // they can cover the tx costs.
        List<Address> allAccounts = flatten(accounts);
        List<BigInteger> allBalances = replicate(balances, periods);
        assertEquals(numDepositors * periods, allAccounts.size());
        assertEquals(allAccounts.size(), allBalances.size());
        giveBalanceTo(repo, allAccounts, DEFAULT_BALANCE);
        Address trsContract = TRSdepositAndMakeLive(repo, periods, t0special, allAccounts,
            allBalances, bonusDeposits);
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);
        assertEquals(totalFunds, TRSgetRemainder(trsContract, repo));

        // We move through each period of the contract and verify the withdrawal amount each time.
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        long periodStartTime = TRSgetStartTime(trsContract, repo);
        for (int i = 1; i <= periods; i++) {
            // Grab account and give it some balance for the transaction cost.
            List<Address> accountsThisRound = accounts.get(i - 1);
            giveAllAccountsBalance(repo, accountsThisRound, DEFAULT_BALANCE);

            List<BigInteger> expectedWithdraw = computeExpectedWithdrawalsAtPeriod(trsContract, repo,
                accountsThisRound, t0special, i, periods);

            NRG = Constants.NRG_TRANSACTION_MAX;
            wipeAllBalances(repo, accountsThisRound);
            TRSbulkWithdrawal(trsContract, repo, accountsThisRound);
            List<BigInteger> withdrawals = getBalancesOf(repo, accountsThisRound);
            assertEquals(expectedWithdraw, withdrawals);

            // move to next period.
            periodStartTime += periodInterval;
            addBlock(periodStartTime);
        }
    }

    /**
     * Tests that when bulkWithdraw is called on a group of addresses that contains depositors and
     * non-depositors that only the depositors are able to withdraw funds from the contract and that
     * nothing is withdrawn by the non-depositors.
     */
    @Test
    public void testBulkWithdrawalSomeLegitSomeNotLegitAddresses() {
        BigInteger bonusDeposits = BigInteger.valueOf(RandomUtils.nextInt(1_000, 100_000));
        int numDepositors = 25;
        int periods = RandomUtils.nextInt(1, 100);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testBulkWithdrawalSomeLegitSomeNotLegitAddresses using bonusDeposits: "
            + bonusDeposits);
        System.out.println("testBulkWithdrawalSomeLegitSomeNotLegitAddresses using periods value: "
            + periods);
        System.out.println("testBulkWithdrawalSomeLegitSomeNotLegitAddresses using t0special value: "
            + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // Put together the depositors and invalids. Only depositors will make deposits, but both
        // parties will be part of the bulk withdrawal.
        List<BigInteger> depositorBalances = getRandomBalances(numDepositors / 2);
        List<Address> depositors = makeAccounts(repo, depositorBalances);
        List<BigInteger> invalidBalances = getRandomBalances(numDepositors / 2);
        List<Address> invalids = makeAccounts(repo, invalidBalances);
        while (!listsAreDisjoint(depositors, invalids)) {
            depositors = makeAccounts(repo, depositorBalances);
            invalids = makeAccounts(repo, invalidBalances);
        }

        // We know our depositors and invalids are disjoint now.. create the contract and have all
        // depositors make their deposits.
        giveBalanceTo(repo, depositors, DEFAULT_BALANCE);
        Address trsContract = TRSdepositAndMakeLive(repo, periods, t0special, depositors,
            depositorBalances, bonusDeposits);

        // Move into an arbitrary period.. we don't care which one.
        long startTime = TRSgetStartTime(trsContract, repo);
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        int newPeriod = RandomUtils.nextInt(1, periods + 1);
        addBlock(startTime + (newPeriod * periodInterval));

        // Perform the bulk withdrawal and check that the invalids received nothing and that the
        // legit accounts received something.
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);
        List<Address> allAddresses = mergeAddresses(depositors, invalids);
        wipeAllBalances(repo, allAddresses);
        TRSbulkWithdrawal(trsContract, repo, allAddresses);

        BigInteger sum = BigInteger.ZERO;
        for (Address address : allAddresses) {
            if (depositors.contains(address)) {
                BigInteger withdraw = repo.getBalance(address);
                sum = sum.add(withdraw);
                assertTrue(withdraw.compareTo(BigInteger.ZERO) > 0);
            } else {
                assertTrue(invalids.contains(address));
                assertEquals(BigInteger.ZERO, repo.getBalance(address));
            }
        }

        // Ensure contract did not give out more funds than it could and that the remaining funds
        // in the contract is the expected value.
        assertTrue(sum.compareTo(totalFunds) <= 0);
        assertEquals(totalFunds.subtract(sum), TRSgetRemainder(trsContract, repo));
    }

    /**
     * Tests bulkWithdraw on a list of accounts, all of which are the same account, and ensures that
     * only one withdrawal was carried out as expected and that the contract has the expected value
     * remaining.
     */
    @Test
    public void testBulkWithdrawalOnSameAddress() {
        BigInteger bonusDeposits = BigInteger.valueOf(RandomUtils.nextInt(1_000, 100_000));
        int numDepositors = 25;
        int periods = RandomUtils.nextInt(1, 100);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testBulkWithdrawalOnSameAddress using bonusDeposits: " + bonusDeposits);
        System.out.println("testBulkWithdrawalOnSameAddress using periods value: " + periods);
        System.out.println("testBulkWithdrawalOnSameAddress using t0special value: " + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // Produce the same account copied numDepositors times.
        BigInteger balance = getRandomBalances(1).get(0);
        Address account = makeAccount(repo, balance);
        List<BigInteger> balances = getFixedBalances(numDepositors, balance);
        List<Address> addresses = replicateAddress(account, numDepositors);
        assertEquals(numDepositors, addresses.size());
        assertEquals(addresses.size(), balances.size());


        // We have our single account make its deposit.
        giveBalanceTo(repo, Collections.singletonList(account), DEFAULT_BALANCE);
        Address trsContract = TRSdepositAndMakeLive(repo, periods, t0special,
            Collections.singletonList(account), Collections.singletonList(balance), bonusDeposits);

        // Move into an arbitrary period.. we don't care which one.
        long startTime = TRSgetStartTime(trsContract, repo);
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        int newPeriod = RandomUtils.nextInt(1, periods + 1);
        addBlock(startTime + (newPeriod * periodInterval));

        // Perform the bulk withdrawal and check that only one withdrawal occurred.
        int currentPeriod = TRSgetCurrentPeriod(trsContract, repo);
        BigInteger expectedWithdraw = computeExpectedWithdrawalAtPeriod(trsContract, repo, account,
            t0special, currentPeriod, periods);
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);

        wipeBalance(repo, account);
        TRSbulkWithdrawal(trsContract, repo, addresses);
        assertEquals(expectedWithdraw, repo.getBalance(account));
        assertTrue(expectedWithdraw.compareTo(totalFunds) <= 0);
        assertEquals(totalFunds.subtract(expectedWithdraw), TRSgetRemainder(trsContract, repo));
    }

    /**
     * Tests calling bulkWithdraw before the contract goes live. In this case the call should not
     * be successful and no state changes should occur.
     */
    @Test
    public void testBulkWithdrawalBeforeLive() {
        int numDepositors = 25;
        int periods = RandomUtils.nextInt(1, 100);
        int t0special = RandomUtils.nextInt(0, 100);
        System.out.println("testBulkWithdrawalBeforeLive using periods value: " + periods);
        System.out.println("testBulkWithdrawalBeforeLive using t0special value: " + t0special);
        IRepositoryCache repo = blockchain.getRepository().startTracking();

        // Create the contract, don't lock or make it live yet.
        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, periods, t0special);

        // Attempt to do a bulk withdrawal.
        List<BigInteger> balances = getRandomBalances(numDepositors);
        List<Address> accounts = makeAccounts(repo, balances);
        wipeAllBalances(repo, accounts);
        TRSbulkWithdrawalWillFail(trsContract, repo, accounts);

        // Now lock the contract and try again... should be the same result.
        lockTRScontract(trsContract, repo);
        TRSbulkWithdrawalWillFail(trsContract, repo, accounts);

        assertAllAccountsHaveZeroBalance(repo, accounts);
        assertEquals(BigInteger.ZERO, TRSgetTotalFunds(trsContract, repo));
        assertEquals(BigInteger.ZERO, TRSgetRemainder(trsContract, repo));
    }

    //<---------------------------TESTS ON THE REAL ERC-20 ACCOUNTS-------------------------------->

    /**
     * Tests that the 4 ERC-20 accounts can each deposit their funds into the contract and that the
     * contract is able to correctly hold their balances.
     */
    @Test
    public void testCheckAccountsDeposits() {
        IRepositoryCache repo = blockchain.getRepository().startTracking();
        Address trsContract = setupAionContract(repo);

        // Check each account has its expected deposit amount in the contract.
        List<Address> accounts = grabETHaccounts();
        List<BigInteger> balances = grabAionTRSdeposits();
        checkTRSdeposits(trsContract, repo, accounts, balances);

        // Check the contract total, facevalue and remainder.
        assertEquals(grabAionTotal(), TRSgetTotalFunds(trsContract, repo));
        assertEquals(grabAionTotalFacevalue(), TRSgetTotalFacevalue(trsContract, repo));
        assertEquals(grabAionTotal(), TRSgetRemainder(trsContract, repo));
    }

    /**
     * This is not a test -- this method generates a useful report.
     *
     * This report prints out the exact amount of funds the accounts withdraw in each period of the
     * contract as well as the total amounts each account withdraws over the full contract lifetime
     * and the amount of funds remaining in the contract once all withdrawal periods end.
     * This report also displays the number of periods in which ZERO coins were withdrawn (we don't
     * want this sort of behaviour).
     *
     * This method is used to compare the results of the various conversion/precision trade-offs we
     * can make.
     */
    @Test
    public void testGenerateAionContractReport() {
        IRepositoryCache repo = blockchain.getRepository().startTracking();
        Address trsContract = setupAionContract(repo);

        // Ensure none of the accounts have any balance so later calculations are correct.
        List<Address> accounts = grabETHaccounts();
        wipeAllBalances(repo, accounts);
        assertAllAccountsHaveZeroBalance(repo, accounts);

        // Make withdrawals each period.
        int periods = grabAionTRSperiods();
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        long time = TRSgetStartTime(trsContract, repo);

        // We only need to track one account for this value since this occurs when fraction = 0 and
        // the same fraction is used in every period.
        BigInteger numZeroWithdrawals = BigInteger.ZERO;
        for (int i = 1; i <= periods; i++) {
            List<BigInteger> balancesBeforeWithdrawal = getCurrentBalances(repo, accounts);
            TRSbulkWithdrawal(trsContract, repo, accounts);
            List<BigInteger> balancesAfterWithdrawal = getCurrentBalances(repo, accounts);

            List<BigInteger> withdraws = getDifferences(balancesBeforeWithdrawal, balancesAfterWithdrawal);
            if (withdraws.get(0).equals(BigInteger.ZERO))
                numZeroWithdrawals = numZeroWithdrawals.add(BigInteger.ONE);
            System.err.println("Period #" + i + " withdrawals: " + withdraws);
            addBlock(time += periodInterval);
        }

        System.err.println("Number of zero withdrawal periods: " + numZeroWithdrawals);
        System.err.println("Account balances at end of contract: " + getCurrentBalances(repo, accounts));
        System.err.println("Remainder at end of contract: " + TRSgetRemainder(trsContract, repo));
    }

    /**
     * Tests that the 4 ERC-20 accounts can call withdraw during everyone of the periods and in
     * none of them does an integer overflow occur.
     *
     * I have personally verified the following conversion/precision amounts to work by hand and by
     * using this method:
     *      1. conversion = 10^0    precision = 10^6
     *      2. conversion = 10^1    precision = 10^4
     *      3. conversion = 10^2    precision = 10^2
     *
     * These are tight bounds in the sense that, for each conversion of the form 10^x, its
     * corresponding precision as listed above as 10^y will cause overflow if the precision is set
     * to 10^(y+1).
     *
     * We cannot increase conversion higher than 10^2 otherwise precision gets so poor that the
     * contract behaviour becomes nonsensical.
     */
    @Test
    public void testOverflow() {
        // The precision & conversion values to play with.
        BigInteger precision = BigInteger.TEN.pow(6);
        setAionConversion(0);

        // Grab the accounts and their deposits adjusted accordingly as specified above.
        List<Address> accounts = grabETHaccounts();
        List<BigInteger> deposits = grabAionTRSdeposits();
        assertEquals(4, accounts.size());
        assertEquals(accounts.size(), deposits.size());

        int periods = grabAionTRSperiods();
        int t0special = grabAionTRSt0special();
        BigInteger total = grabAionTotal();
        BigInteger totalfv = grabAionTotalFacevalue();

        // Test each account over each period.
        for (int accountIndex = 0; accountIndex < 4; accountIndex++) {
            for (int currentPeriod = 0; currentPeriod <= periods; currentPeriod++) {
                checkOverflow(t0special, currentPeriod, periods, precision,
                    deposits.get(accountIndex), total, totalfv);
            }
        }
    }

    /**
     * Verify that the data I pulled from the ethereum TRS contract was representative of the contract
     * in period 9.
     */
    @Test
    public void testVerifyEthereumTRSinPeriod9() {
        IRepositoryCache repo = blockchain.getRepository().startTracking();
        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, ETH_PERIODS.intValueExact(), ETH_T0SPECIAL.intValueExact());
        addBlock(ETH_STARTTIME.longValueExact());

        // Set up the accounts.
        List<Address> accounts = grabETHaccounts();
        List<BigInteger> balances = grabETHdeposits();
        List<BigInteger> withdrawals = grabETHwithdrawals();
        setupAccounts(repo, accounts, balances);

        // Make the deposits and start the contract.
        depositIntoTRScontract(trsContract, repo, accounts, balances);
        lockTRScontract(trsContract, repo);
        repo.addBalance(trsContract, ETH_BONUS);
        startTRScontract(trsContract, repo);
        BigInteger contractTotal = TRSgetTotalFunds(trsContract, repo);
        BigInteger contractTotalFV = TRSgetTotalFacevalue(trsContract, repo);
        BigInteger contractBonus = contractTotal.subtract(contractTotalFV);

        // Check the deployed contract's state against the ERC-20 contract state.
        assertEquals(ETH_TOTAL, contractTotal);
        assertEquals(ETH_TOTALFV, contractTotalFV);
        assertEquals(ETH_BONUS, contractBonus);
        assertEquals(ETH_PERIODS.intValueExact(), TRSgetNumPeriods(trsContract, repo));
        assertEquals(ETH_STARTTIME.longValueExact(), TRSgetStartTime(trsContract, repo));

        // Verify the ethereum contract is in period 9.
        assertEquals(withdrawals, grabETHwithdrawalsAtPeriod(trsContract, repo, 9));
    }

    /**
     * Shows that our period 12 withdrawal data is as expected. This is so that we can be sure that
     * our hard-coded values correspond to the correct values in the contract during period 12 of
     * the ethereum TRS contract.
     *
     * We are going to use these values as our starting point. From period 12 there are 24 periods
     * remaining. The Aion TRS contract will therefore be a 24 period contract whose starting values
     * will be derived from the state of the ethereum contract in its 12'th period.
     */
    @Test
    public void testVerifyOurInitialAmountsCorrespondToPeriod12inEthereumContract() {
        IRepositoryCache repo = blockchain.getRepository().startTracking();
        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, ETH_PERIODS.intValueExact(), ETH_T0SPECIAL.intValueExact());
        addBlock(ETH_STARTTIME.longValueExact());

        // Set up the accounts.
        List<Address> accounts = grabETHaccounts();
        List<BigInteger> balances = grabETHdeposits();
        List<BigInteger> withdrawals = grabETHwithdrawals();
        setupAccounts(repo, accounts, balances);

        // Make the deposits and start the contract.
        depositIntoTRScontract(trsContract, repo, accounts, balances);
        lockTRScontract(trsContract, repo);
        repo.addBalance(trsContract, ETH_BONUS);
        startTRScontract(trsContract, repo);
        BigInteger contractTotal = TRSgetTotalFunds(trsContract, repo);
        BigInteger contractTotalFV = TRSgetTotalFacevalue(trsContract, repo);
        BigInteger contractBonus = contractTotal.subtract(contractTotalFV);

        // Check the deployed contract's state against the ERC-20 contract state.
        assertEquals(ETH_TOTAL, contractTotal);
        assertEquals(ETH_TOTALFV, contractTotalFV);
        assertEquals(ETH_BONUS, contractBonus);
        assertEquals(ETH_PERIODS.intValueExact(), TRSgetNumPeriods(trsContract, repo));
        assertEquals(ETH_STARTTIME.longValueExact(), TRSgetStartTime(trsContract, repo));

        // Grab the amount of coins each account will have withdrawn by the time the Aion TRS goes
        // live...
        assertEquals(grabHardCodedETHwithdrawalsAtPeriod12(),
            grabETHwithdrawalsAtPeriod(trsContract, repo, LAST_WITHDRAW_PERIOD.intValueExact()));
    }

    /**
     * Proofs by Java for tight bounds on key numbers.
     */
    @Test
    public void testBoundProofs() {
        assertTrue(shiftLeft(AION_DEPOSITED_1, 2).compareTo(BigInteger.TWO.pow(57)) >= 0);
        assertTrue(shiftLeft(AION_DEPOSITED_1, 2).compareTo(BigInteger.TWO.pow(58)) < 0);

        assertTrue(shiftLeft(AION_DEPOSITED_2, 2).compareTo(BigInteger.TWO.pow(58)) >= 0);
        assertTrue(shiftLeft(AION_DEPOSITED_2, 2).compareTo(BigInteger.TWO.pow(59)) < 0);

        assertTrue(shiftLeft(AION_DEPOSITED_3, 2).compareTo(BigInteger.TWO.pow(56)) >= 0);
        assertTrue(shiftLeft(AION_DEPOSITED_3, 2).compareTo(BigInteger.TWO.pow(57)) < 0);

        assertTrue(shiftLeft(AION_DEPOSITED_4, 2).compareTo(BigInteger.TWO.pow(56)) >= 0);
        assertTrue(shiftLeft(AION_DEPOSITED_4, 2).compareTo(BigInteger.TWO.pow(57)) < 0);

        assertTrue(shiftLeft(AION_TOTAL, 2).compareTo(BigInteger.TWO.pow(60)) >= 0);
        assertTrue(shiftLeft(AION_TOTAL, 2).compareTo(BigInteger.TWO.pow(61)) < 0);

        assertTrue(shiftLeft(AION_TOTALFV, 2).compareTo(BigInteger.TWO.pow(59)) >= 0);
        assertTrue(shiftLeft(AION_TOTALFV, 2).compareTo(BigInteger.TWO.pow(60)) < 0);

        assertTrue(BigInteger.TEN.pow(6).compareTo(BigInteger.TWO.pow(19)) >= 0);
        assertTrue(BigInteger.TEN.pow(6).compareTo(BigInteger.TWO.pow(21)) < 0);

        assertTrue(BigInteger.TEN.pow(10).compareTo(BigInteger.TWO.pow(33)) >= 0);
        assertTrue(BigInteger.TEN.pow(10).compareTo(BigInteger.TWO.pow(34)) < 0);
    }

    /**
     * Essentially derives the Aion figures from the hard-coded period 12 expected withdrawal amounts
     * since another test case checks the validity of these numbers, and checks our hard-coded Aion
     * values against these figures.
     */
    @Test
    public void testVerifyAionInitialFigures() {
        for (int i = 0; i < 3; i++) {
            setAionConversion(i);

            List<BigInteger> amountsClaimed = bulkShiftLeft(grabHardCodedETHwithdrawalsAtPeriod12(),
                AION_CONVERSION.intValueExact());

            // amountsUnclaimed will be the amounts each account will initial deposit into the Aion TRS.
            List<BigInteger> aionDeposits = grabAionTRSdeposits();
            List<BigInteger> amountsUnclaimed = getDifferences(amountsClaimed, bulkShiftLeft(grabETHdeposits(),
                AION_CONVERSION.intValueExact()));
            assertEquals(aionDeposits, amountsUnclaimed);
            assertEquals(grabAionTotalFacevalue(), sumOf(aionDeposits));
            System.err.println(aionDeposits);

            // check that the total funds left in ethereum contract in period 12 is equal to the total
            // funds put into the Aion contract.
            BigInteger ethereumTotalInPeriod12 = shiftLeft(ETH_TOTAL, AION_CONVERSION.intValueExact())
                .subtract(sumOf(amountsClaimed));
            assertEquals(ethereumTotalInPeriod12, grabAionTotal());

            assertEquals(BigInteger.ZERO.intValueExact(), grabAionTRSt0special());
            assertEquals(ETH_PERIODS.subtract(LAST_WITHDRAW_PERIOD).intValueExact(),
                grabAionTRSperiods());
        }
    }

    //<----------------------------------------HELPERS--------------------------------------------->

    /**
     * Deploys a new Aion TRS contract that uses the accounts migrated over from the ethereum TRS.
     * This method also deposits the appropriate amounts per each account into the contract and
     * locks the contract, deposits the appropriate bonus amount, and starts the contract.
     *
     * Returns the address of the deployed TRS contract.
     */
    private Address setupAionContract(IRepositoryCache repo) {
        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, AION_PERIODS.intValueExact(), AION_T0SPECIAL.intValueExact());
        addBlock(AION_STARTTIME.longValueExact());

        // Set up the Aion accounts with the appropriate balances.
        List<Address> accounts = grabETHaccounts();
        List<BigInteger> balances = grabAionTRSdeposits();
        BigInteger bonus = grabAionTotal().subtract(grabAionTotalFacevalue());
        setupAccounts(repo, accounts, balances);

        // Deposit the balances.
        depositIntoTRScontract(trsContract, repo, accounts, balances);

        // Deposit the bonus funds, lock and start the contract.
        lockTRScontract(trsContract, repo);
        repo.addBalance(trsContract, bonus);
        startTRScontract(trsContract, repo);
        return trsContract;
    }

    /**
     * Checks that each account in accounts exists in the TRS contract at address trsContract and
     * that the i'th account in accounts has the i'th balance in balances in the contract.
     * Also checks that the total funds in the TRS contract is equal to the sum of balances plus
     * bonusDeposits.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param accounts The depositors.
     * @param balances The deposit amounts per depositor.
     * @param bonusDeposits The amount of bonus deposits in the contract.
     */
    private void verifyAccountsInContract(Address trsContract, IRepositoryCache repo,
        List<Address> accounts, List<BigInteger> balances, BigInteger bonusDeposits) {

        int i = 0;
        for (Address account : accounts) {
            assertEquals(balances.get(i), TRSgetAccountDeposited(trsContract, repo, account));
            i++;
        }
        assertEquals(sumOf(balances).add(bonusDeposits), TRSgetTotalFunds(trsContract, repo));
    }

    /**
     * Returns fraction * precision.
     */
    private BigInteger correctToPrecision(BigDecimal fraction, BigInteger precision) {
        return fraction.multiply(new BigDecimal(precision)).toBigInteger();
    }

    /**
     * Returns a list of the amount of coins each account in accounts is expected to be eligible to
     * withdraw in period currentPEriod for a TRS contract at address trsContract that has a
     * t0special value of t0special and has periods number of periods.
     *
     * The returned list is such that the i'th account in accounts is expected to be eligible to
     * withdraw the i'th amount in the returned list.
     */
    private List<BigInteger> computeExpectedWithdrawalsAtPeriod(Address trsContract,
        IRepositoryCache repo, List<Address> accounts, int t0special, int currentPeriod, int periods) {

        List<BigInteger> withdrawals = new ArrayList<>(accounts.size());
        for (Address account : accounts) {
            withdrawals.add(computeExpectedWithdrawalAtPeriod(trsContract, repo, account, t0special,
                currentPeriod, periods));
        }
        return withdrawals;
    }

    /**
     * Returns the amount of coins account is expected to be eligible to withdraw in period
     * currentPeriod for a TRS contract at address trsContract that has a t0special value of
     * t0special and has periods number of periods.
     *
     */
    private BigInteger computeExpectedWithdrawalAtPeriod(Address trsContract, IRepositoryCache repo,
        Address account, int t0special, int currentPeriod, int periods) {

        BigInteger precision = TRSgetPrecision(trsContract, repo);
        return computeExpectedWithdrawalAtPeriod(
            trsContract, repo, account, t0special, currentPeriod, periods, precision);
    }

    /**
     * Returns the amount of coins account is expected to be eligible to withdraw in period
     * currentPeriod for a TRS contract at address trsContract that has a t0special value of
     * t0special and has periods number of periods and has a precision value of precision.
     *
     */
    private BigInteger computeExpectedWithdrawalAtPeriod(Address trsContract, IRepositoryCache repo,
        Address account, int t0special, int currentPeriod, int periods, BigInteger precision) {

        BigInteger deposit = TRSgetAccountDeposited(trsContract, repo, account);
        BigInteger total = TRSgetTotalFunds(trsContract, repo);
        BigInteger totalfv = TRSgetTotalFacevalue(trsContract, repo);
        BigInteger fraction = fraction(t0special, currentPeriod, periods, precision);

        BigInteger numerator = deposit.multiply(total).multiply(fraction);
        BigInteger quotient = numerator.divide(totalfv);
        return quotient.divide(precision);
    }

    /**
     * Returns the fraction of the total funds owed to an account for some current period
     * currentPeriod for a contract that has numPeriods periods and whose special one-off multiplier
     * is t0special.
     *
     * @param t0special The special one-off multiplier of some contract.
     * @param currentPeriod The current period of some contract.
     * @param numPeriods The number of periods in some contract.
     * @return the fraction of total funds owed to some account for some contract.
     */
    private static BigInteger fraction(int t0special, int currentPeriod, int numPeriods, BigInteger precision) {
        BigInteger numerator = BigInteger.valueOf(t0special).add(BigInteger.valueOf(currentPeriod));
        numerator = numerator.multiply(precision);
        BigInteger denominator = BigInteger.valueOf(t0special).add(BigInteger.valueOf(numPeriods));
        return numerator.divide(denominator);
    }

    /**
     * Returns the total amount of coins that account will be able to collect over the lifetime of
     * the TRS contract at address trsContract. It must be that account cannot claim more than this
     * amount after arbitrarily many withdrawals over every period of the contract and it also must
     * be that account will receive this full amount iff account makes at least one withdrawal in
     * the final period of the contract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param account The account whose owed funds is to be computed.
     * @return the total amount of funds owed to account over the contract's lifetime.
     */
    private BigInteger computeTotalOwed(Address trsContract, IRepositoryCache repo, Address account) {
        BigInteger facevalue = TRSgetTotalFacevalue(trsContract, repo);
        BigInteger deposited = TRSgetAccountDeposited(trsContract, repo, account);
        BigInteger totalFunds = TRSgetTotalFunds(trsContract, repo);

        if (facevalue.compareTo(totalFunds) == 0) {
            return deposited;
        } else {
            int periods = TRSgetNumPeriods(trsContract, repo);
            int t0special = TRSgetT0special(trsContract, repo);
            return computeExpectedWithdrawalAtPeriod(
                trsContract, repo, account, t0special, periods, periods);
        }
    }

    /**
     * Returns the precision value used by the TRS contract at address trsContract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @return the precision value used by the contract.
     */
    private BigInteger TRSgetPrecision(Address trsContract, IRepositoryCache repo) {
        byte[] input = Hex.decode("d3b5dc3b");
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        return new BigInteger(result.getOutput());
    }

    /**
     * Makes a bulk withdrawal call on the TRS contract at address trsContract for all of the accounts
     * in beneficiaries. This method directly calls the bulkWithdraw function in the contract. The
     * behaviour of this function is equivalent to calling withdrawTo for each of the accounts in
     * beneficiaries.
     *
     * This method fails if the size of beneficiaries is larger than 25 since we start getting into
     * OUT_OF_NRG territory here.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param beneficiaries The accounts to attempt to withdraw funds to.
     */
    private void TRSbulkWithdrawal(Address trsContract, IRepositoryCache repo, List<Address> beneficiaries) {
        assertTrue(beneficiaries.size() <= 25);
        byte[] input = makeBulkWithdrawInput(beneficiaries);
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
    }

    /**
     * Attempts to make a bulk withdrawal call on the TRS contract at address trsContract for all of
     * the accounts in beneficiaries and expects the call to not be successful. If the call is
     * successful this method will cause the calling test case to fail.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param beneficiaries The withdrawal beneficiaries.
     */
    private void TRSbulkWithdrawalWillFail(Address trsContract, IRepositoryCache repo,
        List<Address> beneficiaries) {

        assertTrue(beneficiaries.size() <= 25);
        byte[] input = makeBulkWithdrawInput(beneficiaries);
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertNotEquals(ResultCode.SUCCESS, result.getResultCode());
    }

    /**
     * Returns the input byte array to the bulkWithdraw function under the assumption that addresses
     * is the list of withdrawal beneficiaries given to the function.
     */
    private byte[] makeBulkWithdrawInput(List<Address> addresses) {
        byte[] offset = new DataWord(16).getData();
        SolidityType type = new DynamicArrayType("address[]");
        byte[] params = ByteUtil.merge(offset, type.encode(toStrings(addresses)));
        return ByteUtil.merge(Hex.decode("2ed94f6c"), params);
    }

    /**
     * Converts the list of addresses to a list of String representations of the addresses.
     */
    private static List<String> toStrings(List<Address> addresses) {
        List<String> stringAddresses = new ArrayList<>();
        for (Address address : addresses) {
            stringAddresses.add(address.toString());
        }
        return stringAddresses;
    }

    /**
     * Returns the amount of funds account is eligible to withdraw from the TRS contract at address
     * trsContract at time time.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param account The account whose fund eligibility is being checked.
     * @param time The time of the withdrawal.
     * @return the amount of funds account is eligible to withdraw.
     */
    private BigInteger TRSfractionEligibleToWithdraw(Address trsContract, IRepositoryCache repo,
        Address account, long time) {

        byte[] input = ByteUtil.merge(Hex.decode("dd39472f"), new DataWord(time).getData());
        ExecutionResult result = makeContractCall(trsContract, repo, account, input);
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        return new BigInteger(result.getOutput());
    }

    /**
     * Makes an attempt to withdraw coins from the TRS contract at address trsContract on behalf of
     * beneficiary and checks that the call is not successful. If the call is successful this method
     * will cause the calling test to fail.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param beneficiary The would-be beneficiary of the withdrawal.
     */
    private void TRSwithdrawFundsForWillFail(Address trsContract, IRepositoryCache repo,
        Address beneficiary) {

        byte[] input = ByteUtil.merge(Hex.decode("72b0d90c"), beneficiary.toBytes());
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertNotEquals(ResultCode.SUCCESS, result.getResultCode());
    }

    /**
     * Makes an attempt to withdraw coins from the TRS contract at address trsContract on behalf of
     * beneficiary and returns true iff a positive amount of coins was successfully withdrawn.
     *
     * Note: the withdrawal here is performed by the contract owner on behalf of beneficiary.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param beneficiary The beneficiary of the withdrawal.
     * @return true iff a positive amount of funds was withdrawn.
     */
    private boolean TRSwithdrawFundsFor(Address trsContract, IRepositoryCache repo, Address beneficiary) {
        byte[] input = ByteUtil.merge(Hex.decode("72b0d90c"), beneficiary.toBytes());
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        byte[] output = result.getOutput();
        return output[output.length - 1] == 0x1;
    }

    /**
     * Returns the interval (or duration) in seconds between each period in the TRS contract at
     * address trsContract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @return the duration in seconds of each period in the contract.
     */
    private int TRSgetPeriodInterval(Address trsContract, IRepositoryCache repo) {
        byte[] input = Hex.decode("4cbf867d");
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        return new BigInteger(result.getOutput()).intValueExact();
    }

    /**
     * Returns the timestamp of the block the TRS contract at address trsContract went live (was
     * started) at.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @return the start time of the contract.
     */
    private long TRSgetStartTime(Address trsContract, IRepositoryCache repo) {
        byte[] input = Hex.decode("ac3dc9aa");
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        return new BigInteger(result.getOutput()).longValueExact();
    }

    /**
     * Returns the period that the trsContract at address trsContract is currently in.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @return the number of periods in the contract.
     */
    private int TRSgetCurrentPeriod(Address trsContract, IRepositoryCache repo) {
        byte[] input = Hex.decode("ef78d4fd");
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        return new BigInteger(result.getOutput()).intValueExact();
    }

    /**
     * Returns the number of periods in the TRS contract at address trsContract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @return the number of periods in the contract.
     */
    private int TRSgetNumPeriods(Address trsContract, IRepositoryCache repo) {
        byte[] input = Hex.decode("a4caeb42");
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        return new BigInteger(result.getOutput()).intValueExact();
    }

    /**
     * Returns the t0special one-off multiplier for the TRS contract at address trsContract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @return the one-off special multiplier.
     */
    private int TRSgetT0special(Address trsContract, IRepositoryCache repo) {
        byte[] input = Hex.decode("90e2b94b");
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        return new BigInteger(result.getOutput()).intValueExact();
    }

    /**
     * Returns the total amount of funds in the TRS contract at address trsContract. This amount is
     * lower bounded by the total "face value" amount, which is the amount that was explicitly
     * deposited by depositors, and may be larger than this amount if bonus deposits are made.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @return the total funds in the contract.
     */
    private BigInteger TRSgetTotalFunds(Address trsContract, IRepositoryCache repo) {
        byte[] input = Hex.decode("2ddbd13a");
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        return new BigInteger(result.getOutput());
    }

    /**
     * Returns the amount of coins that account has deposited into the TRS contract at address
     * trsContract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param account The account whose deposit balance is to be queried.
     * @return the amount of coins account has deposited.
     */
    private BigInteger TRSgetAccountDeposited(Address trsContract, IRepositoryCache repo, Address account) {
        byte[] input = ByteUtil.merge(Hex.decode("cb13cddb"), account.toBytes());
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        return new BigInteger(result.getOutput());
    }

    /**
     * This method will deploy a new TRS contract and will deposit the i'th balance in balances into
     * the contract for the i'th account in accounts and will ensure that none of the accounts in
     * accounts have any balance remaining after this call returns. The contract will then be locked
     * and bonusDeposits amount of coins will be deposited into the contract as a bonus, then the
     * contract will be made live.
     *
     * This method returns the address of the newly deployed TRS contract.
     */
    private Address TRSdepositAndMakeLive(IRepositoryCache repo, int periods, int t0special,
        List<Address> accounts, List<BigInteger> balances, BigInteger bonusDeposits) {

        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, periods, t0special);
        depositIntoTRScontract(trsContract, repo, accounts, balances);
        BigInteger expectedFacevalue = sumOf(balances);
        assertEquals(expectedFacevalue, TRSgetTotalFacevalue(trsContract, repo));
        lockTRScontract(trsContract, repo);
        repo.addBalance(trsContract, bonusDeposits);
        startTRScontract(trsContract, repo);
        assertTrue(TRSisLocked(trsContract, repo));
        assertTrue(TRSisStarted(trsContract, repo));
        wipeAllBalances(repo, accounts);
        assertAllAccountsHaveZeroBalance(repo, accounts);
        return trsContract;
    }

    /**
     * This method will deploy a new TRS contract and will create numDepositors random accounts,
     * each with a random balance in [1000, 100000000] and will have each such account deposit its
     * full funds into the contract. The contract will then be locked and bonusDeposits amount of
     * coins will be deposited into the contract as a bonus, then the contract will be made live.
     *
     * The returned ObjectHolder will hold the following 3 objects:
     *   Address --             contract deploy address
     *   List<Address> --       list of depositors
     *   List<BigInteger> --    list of deposit amounts
     *
     * such that the two lists are the same size and the i'th account corresponds to the i'th amount.
     */
    private ObjectHolder setupTRScontractWithDeposits(IRepositoryCache repo, int periods,
        int t0special, int numDepositors, BigInteger bonusDeposits) {

        ObjectHolder holder = new ObjectHolder();

        List<BigInteger> balances = getRandomBalances(numDepositors);
        List<Address> accounts = makeAccounts(repo, DEFAULT_BALANCE, balances.size());
        Address trsContract = TRSdepositAndMakeLive(repo, periods, t0special, accounts, balances,
            bonusDeposits);

        holder.placeObject(trsContract);
        holder.placeObject(accounts);
        holder.placeObject(balances);
        return holder;
    }

    /**
     * This method will deploy a new TRS contract and will create numDepositors random accounts,
     * each of which will deposit depositAmount amount of coins into the contract. The contract will
     * then be locked and bonusDeposits amount of coins will be deposited into the contract as a
     * bonus, then the contract will be made live.
     *
     * The returned list of Address objects will be of size numDepositors + 1.
     * The first address in this list will be the address of the deployed contract.
     * All remaining addresses in the list will be the addresses of the depositors.
     */
    private List<Address> setupTRScontractFixedDepositAmounts(IRepositoryCache repo, int periods,
        int t0special, int numDepositors, BigInteger depositAmount, BigInteger bonusDeposits) {

        Address trsContract = deployTRScontract(repo);
        initTRScontract(trsContract, repo, periods, t0special);
        List<BigInteger> balances = getFixedBalances(numDepositors, depositAmount);
        List<Address> accounts = makeAccounts(repo, DEFAULT_BALANCE, balances.size());
        depositIntoTRScontract(trsContract, repo, accounts, balances);
        BigInteger expectedFacevalue = sumOf(balances);
        assertEquals(expectedFacevalue, TRSgetTotalFacevalue(trsContract, repo));
        lockTRScontract(trsContract, repo);
        repo.addBalance(trsContract, bonusDeposits);
        startTRScontract(trsContract, repo);
        assertTrue(TRSisLocked(trsContract, repo));
        assertTrue(TRSisStarted(trsContract, repo));
        wipeAllBalances(repo, accounts);
        assertAllAccountsHaveZeroBalance(repo, accounts);

        List<Address> returnAddresses = new ArrayList<>(numDepositors + 1);
        returnAddresses.add(trsContract);
        returnAddresses.addAll(accounts);
        return returnAddresses;
    }

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
        List<Address> depositors, List<BigInteger> amounts) {

        assertEquals(depositors.size(), amounts.size());
        int i = 0;
        for (Address account : depositors) {
            assertTrue(repo.getBalance(account).compareTo(amounts.get(i)) >= 0);
            sendCoinsToTRScontract(trsContract, repo, account, amounts.get(i));
            depositOfBehalfOf(trsContract, repo, account, amounts.get(i), false);
            i++;
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
        List<Address> depositors, List<BigInteger> amounts) {

        assertEquals(depositors.size(), amounts.size());
        int i = 0;
        for (Address account : depositors) {
            assertTrue(repo.getBalance(account).compareTo(amounts.get(i)) >= 0);
            sendCoinsToTRScontract(trsContract, repo, account, amounts.get(i));
            depositOfBehalfOf(trsContract, repo, account, amounts.get(i), true);
            i++;
        }
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
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
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
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
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
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);

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
     * @param sender The sending account.
     * @param amount The amount to send.
     */
    private void sendCoinsToTRScontract(Address trsContract, IRepositoryCache repo, Address sender,
        BigInteger amount) {

        assertTrue(repo.getBalance(sender).compareTo(amount) >= 0);
        BigInteger contractPrevBalance = repo.getBalance(trsContract);
        ExecutionResult result = makeContractCall(trsContract, repo, sender, new byte[1], amount);
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
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        return new BigInteger(result.getOutput());
    }

    /**
     * Attempts to use the account caller to accept an ownership proposal for the contract at address
     * trsContract so that if this call succeeds caller will be the new owner of the contract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param caller The could-be new owner.
     */
    private void TRSacceptOwnership(Address trsContract, IRepositoryCache repo, Address caller) {
        byte[] input = Hex.decode("79ba5097");
        ExecutionResult result = makeContractCall(trsContract, repo, caller, input);
        if (result.getResultCode().equals(ResultCode.SUCCESS)) {
            System.err.println(caller + " has accepted ownership of the TRS contract!");
        }
    }

    /**
     * Proposes a new owner for the TRS contract at address trsContract to be newOwner. After this
     * call newOwner is not yet the owner, newOwner must formally accept the ownership offer through
     * the contract in order to be the real owner of the contract and usurp the current owner.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param newOwner The new owner to propose.
     */
    private void TRSproposeNewOwner(Address trsContract, IRepositoryCache repo, Address newOwner) {
        byte[] input = ByteUtil.merge(Hex.decode("a6f9dae1"), newOwner.toBytes());
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
    }

    /**
     * Returns the address of the new owner of the TRS contract at address trsContract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @return the address of the new contract owner.
     */
    private Address TRSwhoIsNewOwner(Address trsContract, IRepositoryCache repo) {
        byte[] input = Hex.decode("d4ee1d90");
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        return new Address(result.getOutput());
    }

    /**
     * Returns the address of the account that owns the TRS contract at address trsContract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @return the address of the contract owner.
     */
    private Address TRSwhoIsOwner(Address trsContract, IRepositoryCache repo) {
        byte[] input = Hex.decode("8da5cb5b");
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        return new Address(result.getOutput());
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
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        System.err.println("TRS contract has been started.");
    }

    /**
     * Attempts to lock the TRS contract at address trsContract by calling the lock function from
     * the account caller.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param caller The account that makes the lock attempt.
     */
    private void lockTRScontract(Address trsContract, IRepositoryCache repo, Address caller) {
        byte[] input = org.aion.base.util.Hex.decode("f83d08ba");
        ExecutionResult result = makeContractCall(trsContract, repo, caller, input);
        if (result.getResultCode().equals(ResultCode.SUCCESS)) {
            System.err.println("The TRS contract has been locked.");
        } else {
            System.err.println("The TRS contract has NOT been locked.");
        }
    }

    /**
     * Locks the TRS contract at address trsContract.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     */
    private void lockTRScontract(Address trsContract, IRepositoryCache repo) {
        lockTRScontract(trsContract, repo, deployer);
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
        ExecutionResult result = makeContractCall(trsContract, repo, deployer, input);
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
        BigInteger nonce = repo.getNonce(deployer);
        AionTransaction tx = new AionTransaction(nonce.toByteArray(), deployer, trsContract,
            value.toByteArray(), input, NRG, NRG_PRICE);

        BlockContext context = blockchain.createNewBlockInternal(
            blockchain.getBestBlock(),
            Collections.singletonList(tx),
            false,
            blockchain.getBestBlock().getTimestamp());

        TransactionExecutor exec = new TransactionExecutor(tx, context.block, repo, LOGGER_VM);
        exec.setExecutorProvider(new TestVMProvider());
        exec.execute();
        ExecutionResult result = (ExecutionResult) exec.getResult();
        assertEquals(ResultCode.SUCCESS, result.getResultCode());
        System.err.println("TRS contract successfully initialized.");

        // finalize the contract init.
        input = Hex.decode("72a02f1d");
        nonce = nonce.add(BigInteger.ONE);
        tx = new AionTransaction(nonce.toByteArray(), deployer, trsContract, value.toByteArray(),
            input, NRG, NRG_PRICE);

        context = blockchain.createNewBlockInternal(
            blockchain.getBestBlock(),
            Collections.singletonList(tx),
            false,
            blockchain.getBestBlock().getTimestamp());

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
     * @param repo The repo.
     * @return the TRS contract address.
     */
    private Address deployTRScontract(IRepositoryCache repo) {
        Address contract = deployContract(repo, getDeployTx(repo));
        addBlock(blockchain.getBestBlock().getTimestamp());
        return contract;
    }

    /**
     * Returns a transaction that will deploy a new TRS contract.
     *
     * @param repo The repo.
     * @return a deployment transaction.
     */
    private AionTransaction getDeployTx(IRepositoryCache repo) {
        byte[] deployCode = Hex.decode(TRSdeployCode());
        BigInteger nonce = repo.getNonce(deployer);
        BigInteger value = BigInteger.ZERO;
        AionTransaction tx = new AionTransaction(nonce.toByteArray(), deployer, null,
            value.toByteArray(), deployCode, NRG, NRG_PRICE);
        return tx;
    }

    /**
     * Deploys a contract named contractName and checks the state of the deployed contract and the
     * contract deployer and returns the address of the contract once finished.
     *
     * @param repo The repo.
     * @param tx A deployment contract.
     * @return the address of the newly deployed TRS contract.
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
     * Calls the TRS contract at address trsContract from account caller using the specified input
     * and returns the result of the call.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param caller The caller.
     * @param input The call input.
     * @return the result of the contract call.
     */
    private ExecutionResult makeContractCall(Address trsContract, IRepositoryCache repo,
        Address caller, byte[] input) {

        return makeContractCall(trsContract, repo, caller, input, BigInteger.ZERO);
    }

    /**
     * Calls the TRS contract at address trsContract from account caller using the specified input
     * and transfers value amount of coins to the contract and returns the result of the call.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param caller The caller.
     * @param input The call input.
     * @param value The amount of value to transfer.
     * @return the result of the contract call.
     */
    private ExecutionResult makeContractCall(Address trsContract, IRepositoryCache repo,
        Address caller, byte[] input, BigInteger value) {

        BigInteger nonce = repo.getNonce(caller);
        AionTransaction tx = new AionTransaction(nonce.toByteArray(), caller, trsContract,
            value.toByteArray(), input, NRG, NRG_PRICE);

        BlockContext context = blockchain.createNewBlockInternal(
            blockchain.getBestBlock(),
            Collections.singletonList(tx),
            false,
            blockchain.getBestBlock().getTimestamp());

        TransactionExecutor exec = new TransactionExecutor(tx, context.block, repo, LOGGER_VM);
        exec.setExecutorProvider(new TestVMProvider());
        exec.execute();
        return (ExecutionResult) exec.getResult();
    }

    /**
     * Adds a new block to the blockchain with a timestamp value of time.
     *
     * @param time The timestamp to add to the block.
     */
    private void addBlock(long time) {
        AionBlock block = blockchain.createNewBlockInternal(
            blockchain.getBestBlock(),
            new ArrayList<>(),
            false,
            time).block;
        assertEquals(ImportResult.IMPORTED_BEST, blockchain.tryToConnect(block));
    }

    /**
     * Asserts that all addresses in accounts have zero balance. If this condition is untrue then
     * the calling test will fail.
     *
     * @param repo The repo.
     * @param accounts the accounts to check.
     */
    private static void assertAllAccountsHaveZeroBalance(IRepositoryCache repo, List<Address> accounts) {
        for (Address account : accounts) {
            assertEquals(BigInteger.ZERO, repo.getBalance(account));
        }
    }

    /**
     * Returns a list of |initBalances| new random accounts such that the i'th account in the
     * returned list has a balance equal to the i'th balance in initBalances.
     *
     * @param repo The repo.
     * @param initBalances The initial balances of the accounts.
     * @return a list of |initBalances| new accounts.
     */
    private List<Address> makeAccounts(IRepositoryCache repo, List<BigInteger> initBalances) {
        List<Address> accounts = new ArrayList<>(initBalances.size());
        for (BigInteger balance : initBalances) {
            accounts.add(makeAccount(repo, balance));
        }
        return accounts;
    }

    /**
     * Returns a list of numAccounts new random accounts, each of which is saved in repo with an
     * initial balance corresponding to the balance listed in initBalances.
     *
     * @param repo the repo.
     * @param initBalances The initial balances for each accounts.
     * @param numAccounts The number of accounts.
     * @return a list of numAccounts new accounts.
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

    /**
     * Returns a list of numBalances random balances in the range [1000, 100000000].
     *
     * @param numBalances The size of the list to return.
     * @return a list of numBalances random balances.
     */
    private List<BigInteger> getRandomBalances(int numBalances) {
        List<BigInteger> balances = new ArrayList<>(numBalances);
        for (int i = 0; i < numBalances; i++) {
            balances.add(BigInteger.valueOf(RandomUtils.nextInt(1_000, 100_000_001)));
        }
        return balances;
    }

    /**
     * Returns a list of numBalances balances each of which is equal to balance.
     *
     * @param numBalances The size of the list to return.
     * @param balance The balance to repeat in the list.
     * @return a list of numBalances balances.
     */
    private List<BigInteger> getFixedBalances(int numBalances, BigInteger balance) {
        List<BigInteger> balances = new ArrayList<>(numBalances);
        for (int i = 0; i < numBalances; i++) {
            balances.add(balance);
        }
        return balances;
    }

    /**
     * Removes all balance from each account in accounts in repo so that each account has zero
     * balance.
     */
    private static void wipeAllBalances(IRepositoryCache repo, List<Address> accounts) {
        for (Address account : accounts) {
            wipeBalance(repo, account);
        }
    }

    /**
     * Removes all balance from account in repo so that it has zero balance.
     */
    private static void wipeBalance(IRepositoryCache repo, Address account) {
        BigInteger balance = repo.getBalance(account);
        repo.addBalance(account, balance.negate());
        assertEquals(BigInteger.ZERO, repo.getBalance(account));
    }

    /**
     * Returns the sum of all the numbers in nums.
     *
     * @param nums The numbers to sum.
     * @return the sum of nums.
     */
    private BigInteger sumOf(List<BigInteger> nums) {
        BigInteger sum = BigInteger.ZERO;
        for (BigInteger num : nums) {
            sum = sum.add(num);
        }
        return sum;
    }

    /**
     * Makes multiple bulkWithdraw requests for each account in accounts into the TRS contract at
     * address trsContract over all of the periods that have a duration of periodInterval seconds.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param periods The number of periods in the contract.
     * @param periodInterval The duration (in seconds) of each period.
     * @param accounts The accounts in the contract.
     */
    private void makeExcessBulkWithdrawalsInAllPeriods(Address trsContract, IRepositoryCache repo,
        int periods, int periodInterval, List<Address> accounts) {

        for (int i = 0; i < periods; i++) {
            for (int j = 0; j < periodInterval; j++) {
                // We make withdrawal attempts each second right into the final period.
                TRSbulkWithdrawal(trsContract, repo, accounts);
                addBlock(blockchain.getBestBlock().getTimestamp() + 1);
            }
        }
    }

    /**
     * Makes multiple withdrawal requests for each account in accounts into the TRS contract at
     * address trsContract over all of the periods that have a duration of periodInterval seconds.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param repo The repo.
     * @param periods The number of periods in the contract.
     * @param periodInterval The duration (in seconds) of each period.
     * @param accounts The accounts in the contract.
     */
    private void makeExcessWithdrawalsInAllPeriods(Address trsContract, IRepositoryCache repo,
        int periods, int periodInterval, List<Address> accounts) {

        for (int i = 0; i < periods; i++) {
            for (int j = 0; j < periodInterval; j++) {
                // We make withdrawal attempts each second right into the final period.
                for (Address account : accounts) {
                    TRSwithdrawFundsFor(trsContract, repo, account);
                }
                addBlock(blockchain.getBestBlock().getTimestamp() + 1);
            }
        }
    }

    /**
     * Returns a 'flattened' version of addresses by replacing each list of addresses with the
     * addresses it holds. If addresses is to be traversed from list to list in order then this same
     * ordering is preserved by the returned flattened list.
     *
     * @param addresses The nested list to flatten.
     * @return the flattened list.
     */
    private static List<Address> flatten(List<List<Address>> addresses) {
        List<Address> flatList = new ArrayList<>();
        for (List<Address> list : addresses) {
            flatList.addAll(list);
        }
        return flatList;
    }

    /**
     * Returns a new list that is of length |nums| * replications and consists of the list nums
     * followed by replications number of repeats of itself.
     *
     * @param nums The list of numbers to replicate.
     * @param replications The number of replications to make.
     * @return the replicated list.
     */
    private static List<BigInteger> replicate(List<BigInteger> nums, int replications) {
        List<BigInteger> numbers = new ArrayList<>();
        for (int i = 0; i < replications; i++) {
            numbers.addAll(nums);
        }
        return numbers;
    }

    /**
     * Creates a list of size replications that contains replications copies of the address address.
     *
     * @param address The address to copy.
     * @param replications The number of copies to make.
     * @return the list of the same address replications times over.
     */
    private List<Address> replicateAddress(Address address, int replications) {
        List<Address> addresses = new ArrayList<>(replications);
        for (int i = 0; i < replications; i++) {
            addresses.add(address);
        }
        return addresses;
    }

    /**
     * Adds balance amount of coins to each account in accounts.
     *
     * @param repo The repo.
     * @param accounts The accounts whose balances are to be increased.
     * @param balance The amount of balance to increase each account by.
     */
    private static void giveBalanceTo(IRepositoryCache repo, List<Address> accounts, BigInteger balance) {
        for (Address account : accounts) {
            repo.addBalance(account, balance);
        }
    }

    /**
     * Returns a list of balances of size |accounts| such that the i'th account in accounts has an
     * account balance equal to the i'th balance in the returned list.
     *
     * @param repo The repo.
     * @param accounts The accounts whose balances are to be retrieved.
     * @return the balances of the accounts in accounts.
     */
    private static List<BigInteger> getBalancesOf(IRepositoryCache repo, List<Address> accounts) {
        List<BigInteger> balances = new ArrayList<>(accounts.size());
        for (Address account : accounts) {
            balances.add(repo.getBalance(account));
        }
        return balances;
    }

    /**
     * Gives each account in accounts balance more coins.
     *
     * @param repo The repo.
     * @param accounts The accounts whose balance is to be increased.
     * @param balance The amount of funds to give to each account in accounts.
     */
    private static void giveAllAccountsBalance(IRepositoryCache repo, List<Address> accounts,
        BigInteger balance) {

        for (Address account : accounts) {
            repo.addBalance(account, DEFAULT_BALANCE);
        }
    }

    /**
     * Takes an ethereum address and pads it with zero bytes on the ending until it is the correct
     * size of an Aion address.
     *
     */
    private static Address toPaddedAddress(String address) {
        byte[] padded = new byte[Address.ADDRESS_LEN];
        byte[] asBytes = Hex.decode(address);
        System.arraycopy(asBytes, 0, padded, 0, asBytes.length);
        return new Address(padded);
    }


    /**
     * Returns true iff addresses1 and addresses2 are disjoint lists.
     */
    private static boolean listsAreDisjoint(List<Address> addresses1, List<Address> addresses2) {
        boolean areDisjoint = true;
        for (Address address : addresses1) {
            if (addresses2.contains(address)) {
                areDisjoint = false;
            }
        }
        for (Address address : addresses2) {
            if (addresses1.contains(address)) {
                areDisjoint = false;
            }
        }
        return areDisjoint;
    }

    /**
     * Returns a list with all addresses in addresses1 and addresses2 in a random ordering.
     *
     * @param addresses1 The first list of addresses.
     * @param addresses2 The second list of addresses.
     * @return the merged and shuffled lists.
     */
    private static List<Address> mergeAddresses(List<Address> addresses1, List<Address> addresses2) {
        List<Address> mergedAddresses = new ArrayList<>(addresses1);
        mergedAddresses.addAll(addresses2);
        Collections.shuffle(mergedAddresses);
        return mergedAddresses;
    }

    /**
     * Creates each account in accounts in the repo such that the i'th account in accounts has an
     * initial balance equal to the i'th balance in balances.
     */
    private void setupAccounts(IRepositoryCache repo, List<Address> accounts, List<BigInteger> balances) {
        assertEquals(accounts.size(), balances.size());
        int size = accounts.size();
        for (int i = 0; i < size; i++) {
            setupAccount(repo, accounts.get(i), balances.get(i));
        }
    }

    /**
     * Creates a new account account int he repo with initial balance balance.
     */
    private void setupAccount(IRepositoryCache repo, Address account, BigInteger balance) {
        repo.createAccount(account);
        repo.addBalance(account, balance.add(DEFAULT_BALANCE));
    }

    /**
     * Checks that the TRS contract at address trsContract has each account in accounts recorded into
     * it with a deposit balance corresponding to expectedBalances, so that the i'th expectedBalance
     * belongs to the i'th account.
     */
    private void checkTRSdeposits(Address trsContract, IRepositoryCache repo, List<Address> accounts,
        List<BigInteger> expectedBalances) {

        assertEquals(accounts.size(), expectedBalances.size());
        int i = 0;
        for (Address account : accounts) {
            assertEquals(expectedBalances.get(i), TRSgetAccountDeposited(trsContract, repo, account));
            i++;
        }
    }

    /**
     * Checks the overflow conditions for a TRS contract corresponding to the two functions most at
     * risk: availableForWithdrawalAt() and _withdrawTo()
     *
     * The TRS contract uses uint128 and this checker ensures that each uint128 that is created at
     * each stage in the computations does not overflow given the specified parameters.
     */
    private void checkOverflow(int t0special, int currentPeriod, int periods, BigInteger precision,
        BigInteger deposit, BigInteger total, BigInteger totalfv) {

        System.err.println("Checking period #" + currentPeriod + " Deposit amount: " + deposit + " Totalfv: " + totalfv);
        BigInteger threshold = BigInteger.TWO.pow(128);
        BigInteger special = BigInteger.valueOf(t0special);
        BigInteger currPeriod = BigInteger.valueOf(currentPeriod);
        BigInteger numPeriods = BigInteger.valueOf(periods);

        // Check all BigInts are less than threshold.
        assertTrue(precision.compareTo(threshold) < 0);
        assertTrue(deposit.compareTo(threshold) < 0);
        assertTrue(total.compareTo(threshold) < 0);
        assertTrue(totalfv.compareTo(threshold) < 0);

        // Check computing fraction does not overflow.
        BigInteger step1 = special.add(currPeriod);
        assertTrue(step1.compareTo(threshold) < 0);
        BigInteger step2 = step1.multiply(precision);
        assertTrue(step2.compareTo(threshold) < 0);
        BigInteger step3 = special.add(numPeriods);
        assertTrue(step3.compareTo(threshold) < 0);
        BigInteger fraction = step2.divide(step3);
        assertTrue(fraction.compareTo(threshold) < 0);

        // Check computing the withdrawable amount does not overflow.
        BigInteger step4 = deposit.multiply(fraction).multiply(total);
        assertTrue(step4.compareTo(threshold) < 0);
        BigInteger step5 = step4.divide(totalfv);
        assertTrue(step5.compareTo(threshold) < 0);
        BigInteger step6 = step5.divide(precision);
        assertTrue(step6.compareTo(threshold) < 0);

        // Check that multiplying the end result by 10^10 still does not overflow.
        BigInteger result = step6.multiply(BigInteger.TEN.pow(10));
        assertTrue(result.compareTo(threshold) < 0);
    }

    /**
     * Returns a list such that the i'th value in the returned list is equal to the i'th value in
     * newBalances minus the i'th value in oldBalances.
     */
    private List<BigInteger> getDifferences(List<BigInteger> oldBalances, List<BigInteger> newBalances) {
        assertEquals(oldBalances.size(), newBalances.size());
        List<BigInteger> diffs = new ArrayList<>();
        int size = oldBalances.size();
        for (int i = 0; i < size; i++) {
            diffs.add(newBalances.get(i).subtract(oldBalances.get(i)));
        }
        return diffs;
    }

    /**
     * Returns a list of balances such that the i'th balance in the returned list is the balance
     * of the i'th account in accounts.
     */
    private List<BigInteger> getCurrentBalances(IRepositoryCache repo, List<Address> accounts) {
        List<BigInteger> balances = new ArrayList<>();
        for (Address account : accounts) {
            balances.add(repo.getBalance(account));
        }
        return balances;
    }

    /**
     * Returns the initial amounts each of the 4 accounts in the TRS contract will transfer over
     * into the Aion TRS contract.
     *
     * The AION_CONVERSION value affects these results!
     */
    private static List<BigInteger> grabAionTRSdeposits() {
        List<BigInteger> deposits = new ArrayList<>(4);
        deposits.add(shiftLeft(AION_DEPOSITED_1, AION_CONVERSION.intValueExact()));
        deposits.add(shiftLeft(AION_DEPOSITED_2, AION_CONVERSION.intValueExact()));
        deposits.add(shiftLeft(AION_DEPOSITED_3, AION_CONVERSION.intValueExact()));
        deposits.add(shiftLeft(AION_DEPOSITED_4, AION_CONVERSION.intValueExact()));
        return deposits;
    }

    /**
     * Returns the t0special amount for the Aion contract.
     */
    private static int grabAionTRSt0special() {
        return AION_T0SPECIAL.intValueExact();
    }

    /**
     * Returns the number of periods in the Aion contract.
     */
    private static int grabAionTRSperiods() {
        return AION_PERIODS.intValueExact();
    }

    /**
     * Returns the sum of deposits in the Aion contract.
     *
     * The AION_CONVERSION value affects these results!
     */
    private static BigInteger grabAionTotalFacevalue() {
        return shiftLeft(AION_TOTALFV, AION_CONVERSION.intValueExact());
    }

    /**
     * Returns the total amount of funds in the Aion contract.
     *
     * The AION_CONVERSION value affects these results!
     */
    private static BigInteger grabAionTotal() {
        return shiftLeft(AION_TOTAL, AION_CONVERSION.intValueExact());
    }

    /**
     * Returns all the addresses in the ethereum TRS contract.
     *
     * Always returns these addresses in the exact same order.
     */
    private static List<Address> grabETHaccounts() {
        List<Address> accounts = new ArrayList<>(4);
        accounts.add(toPaddedAddress(ACCOUNT_1));
        accounts.add(toPaddedAddress(ACCOUNT_2));
        accounts.add(toPaddedAddress(ACCOUNT_3));
        accounts.add(toPaddedAddress(ACCOUNT_4));
        return accounts;
    }

    /**
     * Grabs the deposits made by the accounts in the ethereum TRS contract.
     *
     * NO inflation/conversion is made to these deposit amounts, they are taken straight from the
     * ethereum TRS side and are not adjusted.
     *
     * The returned order is such that, given the list of accounts produced by grabETHaccounts(),
     * the i'th account in that list has deposited an amount equal to the i'th amount in this
     * return list.
     */
    private static List<BigInteger> grabETHdeposits() {
        List<BigInteger> deposits = new ArrayList<>();
        deposits.add(DEPOSITED_1);
        deposits.add(DEPOSITED_2);
        deposits.add(DEPOSITED_3);
        deposits.add(DEPOSITED_4);
        return deposits;
    }

    /**
     * Grabs the amounts the accounts in the ethereum TRS contract have currently withdrawn.
     *
     * NO inflation/conversion is made to these withdrawal amounts, they are taken straight from the
     * ethereum TRS side and are not adjusted.
     *
     * The returned order is such that, given the list of accounts produced by grabETHaccounts(),
     * the i'th account in that list has withdrawn an amount equal to the i'th amount in this
     * return list.
     */
    private static List<BigInteger> grabETHwithdrawals() {
        List<BigInteger> withdrawals = new ArrayList<>();
        withdrawals.add(WITHDRAWN_1);
        withdrawals.add(WITHDRAWN_2);
        withdrawals.add(WITHDRAWN_3);
        withdrawals.add(WITHDRAWN_4);
        return withdrawals;
    }

    /**
     * Returns the hard-coded amounts that each account has withdrawn in period 12 on the ethereum
     * side.
     *
     * Returns them in the order account 1,2,3,4 every time.
     */
    private static List<BigInteger> grabHardCodedETHwithdrawalsAtPeriod12() {
        List<BigInteger> withdrawals = new ArrayList<>();
        withdrawals.add(LAST_WITHDRAW_1);
        withdrawals.add(LAST_WITHDRAW_2);
        withdrawals.add(LAST_WITHDRAW_3);
        withdrawals.add(LAST_WITHDRAW_4);
        return withdrawals;
    }

    /**
     * Returns a list of the amounts each of the accounts in the ethereum TRS contract will have
     * withdrawn (or be eligible to withdraw) in period number period.
     *
     * NO adjustments are made to these numbers!
     *
     * The returned order is such that, given the list of accounts produced by grabETHaccounts(),
     * the i'th account in that list will be eligible to withdraw an amount equal to the i'th amount
     * in this return list.
     *
     * @param trsContract Address of deployed TRS contract.
     * @param  repo The repo.
     * @param period The period to query.
     */
    private List<BigInteger> grabETHwithdrawalsAtPeriod(Address trsContract, IRepositoryCache repo, int period) {
        List<Address> ERCaccounts = grabETHaccounts();
        List<BigInteger> withdrawals = new ArrayList<>();
        for (Address account : ERCaccounts) {
            withdrawals.add(computeExpectedWithdrawalAtPeriod(
                trsContract, repo, account, ETH_T0SPECIAL.intValueExact(), period,
                ETH_PERIODS.intValueExact(), ETH_PRECISION));
        }
        return withdrawals;
    }

    /**
     * Returns each number in numbers with numShifts zeros in its least significant digits.
     */
    private static List<BigInteger> bulkShiftLeft(List<BigInteger> numbers, int numShifts) {
        List<BigInteger> nums = new ArrayList<>();
        for (BigInteger num : numbers) {
            nums.add(shiftLeft(num, numShifts));
        }
        return nums;
    }

    /**
     * Returns number with numShifts zeros in its least significant digits.
     */
    private static BigInteger shiftLeft(BigInteger number, int numShifts) {
        return number.multiply(BigInteger.TEN.pow(numShifts));
    }

    /**
     * Makes bulk withdrawals for all accounts in accounts in the TRS contract at address trsContract
     * from the current period up into - and including! - period number stopPeriod.
     */
    private void makeWithdrawalsUntilPeriod(Address trsContract, IRepositoryCache repo,
        List<Address> accounts, int stopPeriod) {

        long time = TRSgetStartTime(trsContract, repo);
        int periodInterval = TRSgetPeriodInterval(trsContract, repo);
        while (TRSgetCurrentPeriod(trsContract, repo) <= stopPeriod) {
            TRSbulkWithdrawal(trsContract, repo, accounts);
            addBlock(time += periodInterval);
        }
    }

    /**
     * Simple class that holds a list of objects for methods that require returning multiple diverse
     * objects.
     */
    private class ObjectHolder {
        private List<Object> holdings;

        ObjectHolder() {
            this.holdings = new ArrayList<>();
        }

        void placeObject(Object o) {
            this.holdings.add(o);
        }

        Object grabObjectAtPosition(int position) {
            assertTrue(position <= this.holdings.size() - 1);
            return this.holdings.get(position);
        }
    }

    /**
     * Returns a Hex String of the deployment code for the TRS contract.
     * @return
     */
    private String TRSdeployCode() {
        return "6050604052600060076000509090555b3360006000508282909180600101839055555050505b61002a565b61163d806100396000396000f300605060405236156101d5576000356c01000000000000000000000000900463ffffffff168063144fa6d7146101d9578063184274fc146102055780632392b0f01461023a5780632ddbd13a1461025e5780632ed94f6c14610288578063346a76e7146102e75780633a63aa8f146103465780633ccfd60b1461039957806343c885ba146103c75780634cbf867d146103f557806354469aea1461041f578063544736e6146104435780636ef610921461047157806372a02f1d146104b157806372b0d90c146104c757806377b7aa2c1461050b5780637965f1111461054357806379ba5097146105ab5780638da5cb5b146105c157806390e2b94b146105f2578063a06842511461061c578063a191fe2814610646578063a4caeb4214610673578063a6f9dae11461069d578063ac3dc9aa146106c9578063c255fa40146106f3578063c3af702e14610709578063cb13cddb14610733578063cf30901214610773578063d3b5dc3b146107a1578063d4ee1d90146107cb578063dd39472f146107fc578063e6c2776e14610834578063ece20f3614610869578063ef78d4fd1461087f578063f83d08ba146108a9578063f9df65eb146108bf578063fbb0eb8b146108ed578063fc0c546a14610917576101d5565b5b5b005b34156101e55760006000fd5b61020360048080806010013590359091602001909192905050610948565b005b34156102115760006000fd5b61023860048080806010013590359091602001909192908035906010019091905050610980565b005b34156102465760006000fd5b61025c6004808035906010019091905050610a71565b005b341561026a5760006000fd5b610272610b0c565b6040518082815260100191505060405180910390f35b34156102945760006000fd5b6102e560048080359060100190820180359060100191919080806020026010016040519081016040528093929190818152601001838360200280828437820191505050505050909091905050610b15565b005b34156102f35760006000fd5b61034460048080359060100190820180359060100191919080806010026010016040519081016040528093929190818152601001838360100280828437820191505050505050909091905050610b83565b005b34156103525760006000fd5b6103836004808035906010019091908035906010019091908035906010019091908035906010019091905050610c35565b6040518082815260100191505060405180910390f35b34156103a55760006000fd5b6103ad610c95565b604051808215151515815260100191505060405180910390f35b34156103d35760006000fd5b6103db610cce565b604051808215151515815260100191505060405180910390f35b34156104015760006000fd5b610409610ce1565b6040518082815260100191505060405180910390f35b341561042b5760006000fd5b6104416004808035906010019091905050610ce6565b005b341561044f5760006000fd5b610457610d39565b604051808215151515815260100191505060405180910390f35b341561047d5760006000fd5b61049b60048080806010013590359091602001909192905050610d67565b6040518082815260100191505060405180910390f35b34156104bd5760006000fd5b6104c5610d89565b005b34156104d35760006000fd5b6104f160048080806010013590359091602001909192905050610de6565b604051808215151515815260100191505060405180910390f35b34156105175760006000fd5b61052d6004808035906010019091905050610f89565b6040518082815260100191505060405180910390f35b341561054f5760006000fd5b6105a960048080359060100190919080359060100190820180359060100191919080806010026010016040519081016040528093929190818152601001838360100280828437820191505050505050909091905050610fe2565b005b34156105b75760006000fd5b6105bf611166565b005b34156105cd5760006000fd5b6105d56111f3565b604051808383825281601001526020019250505060405180910390f35b34156105fe5760006000fd5b610606611202565b6040518082815260100191505060405180910390f35b34156106285760006000fd5b61063061120b565b6040518082815260100191505060405180910390f35b34156106525760006000fd5b6106716004808035906010019091908035906010019091905050611214565b005b341561067f5760006000fd5b610687611281565b6040518082815260100191505060405180910390f35b34156106a95760006000fd5b6106c76004808080601001359035909160200190919290505061128a565b005b34156106d55760006000fd5b6106dd6112c6565b6040518082815260100191505060405180910390f35b34156106ff5760006000fd5b6107076112cf565b005b34156107155760006000fd5b61071d6113d9565b6040518082815260100191505060405180910390f35b341561073f5760006000fd5b61075d600480808060100135903590916020019091929050506113e2565b6040518082815260100191505060405180910390f35b341561077f5760006000fd5b610787611404565b604051808215151515815260100191505060405180910390f35b34156107ad5760006000fd5b6107b5611417565b6040518082815260100191505060405180910390f35b34156107d75760006000fd5b6107df61141d565b604051808383825281601001526020019250505060405180910390f35b34156108085760006000fd5b61081e600480803590601001909190505061142c565b6040518082815260100191505060405180910390f35b34156108405760006000fd5b6108676004808080601001359035909160200190919290803590601001909190505061146b565b005b34156108755760006000fd5b61087d61154d565b005b341561088b5760006000fd5b61089361158c565b6040518082815260100191505060405180910390f35b34156108b55760006000fd5b6108bd6115a7565b005b34156108cb5760006000fd5b6108d36115e6565b604051808215151515815260100191505060405180910390f35b34156108f95760006000fd5b6109016115f9565b6040518082815260100191505060405180910390f35b34156109235760006000fd5b61092b611602565b604051808383825281601001526020019250505060405180910390f35b600060005080600101549054339091149190141615156109685760006000fd5b8181600860005082828255906001015550505b5b5050565b600060005080600101549054339091149190141615156109a05760006000fd5b600660019054906101000a900460ff161580156109c257506000600760005054145b15156109ce5760006000fd5b600f60009054906101000a900460ff161515156109eb5760006000fd5b80600a60005060008585825281601001526020019081526010016000209050600082828250540192505081909090555080600b600082828250540192505081909090555082827fc6dcd8d437d8b3537583463d84a6ba9d7e3e013fa4e004da9b6dee1482038be5846040518082815260100191505060405180910390a25b5b5b5b505050565b600060006000508060010154905433909114919014161515610a935760006000fd5b600660009054906101000a900460ff161515610aaf5760006000fd5b600660019054906101000a900460ff168015610ad057506000600760005054145b1515610adc5760006000fd5b8160076000508190909055503031905080600d60005081909090555080600c6000508190909055505b5b5b5b5050565b600d6000505481565b6000600f60009054906101000a900460ff16151515610b345760006000fd5b600090505b8151811015610b7d57610b6e8282815181101515610b5357fe5b90601001906020020180601001519051610de663ffffffff16565b505b8080600101915050610b39565b5b5b5050565b6000600060006000600060006000508060010154905433909114919014161515610bad5760006000fd5b6bffffffffffffffffffffffff9450600093505b8551841015610c2b5760608685815181101515610bda57fe5b906010019060100201519060020a9004600092509250848685815181101515610bff57fe5b90601001906010020151169050610c1d83838361098063ffffffff16565b5b8380600101945050610bc1565b5b5b505050505050565b600060006000610c4a8561142c63ffffffff16565b9150612710600b6000505485848a0202811515610c6357fe5b04811515610c6d57fe5b04905085811115610c82578581039250610c8b565b60009250610c8b565b5050949350505050565b6000600f60009054906101000a900460ff16151515610cb45760006000fd5b610cc333610de663ffffffff16565b9050610cca565b5b90565b600660009054906101000a900460ff1681565b600381565b60006000508060010154905433909114919014161515610d065760006000fd5b600f60009054906101000a900460ff16151515610d235760006000fd5b610d33338361098063ffffffff16565b5b5b5b50565b6000600660019054906101000a900460ff168015610d5d5750600060076000505414155b9050610d64565b90565b600e600050602052818160005260105260306000209050600091509150505481565b60006000508060010154905433909114919014161515610da95760006000fd5b600660009054906101000a900460ff16151515610dc65760006000fd5b6001600660006101000a81548160ff0219169083151502179055505b5b5b565b6000600060006000600660019054906101000a900460ff168015610e105750600060076000505414155b1515610e1c5760006000fd5b600f60009054906101000a900460ff16151515610e395760006000fd5b600a60005060008787825281601001526020019081526010016000209050600050549250600e60005060008787825281601001526020019081526010016000209050600050549150610e98838342600d60005054610c3563ffffffff16565b90506000811415610eac5760009350610f7e565b600b60005054600d600050548402811515610ec357fe5b0482820111151515610ed55760006000fd5b85856108fc83908115029060405160006040518083038185898989f19450505050505080600e60005060008888825281601001526020019081526010016000209050600082828250540192505081909090555080600c600082828250540392505081909090555085857fb061022b0142dafc69e0206f0d1602f87e19faa0bd2befbf1d557f50a0dbb78e846040518082815260100191505060405180910390a260019350610f7e565b5b5b50505092915050565b60006000826007600050541115610fa35760009150610fdc565b600160036007600050548503811515610fb857fe5b04019050600460005054811115610fd457600460005054905080505b809150610fdc565b50919050565b6000600060006000600060006000600050806001015490543390911491901416151561100e5760006000fd5b600660019054906101000a900460ff1615801561103057506000600760005054145b151561103c5760006000fd5b6010600050548814151561104f5761115a565b6001601060008282825054019250508190909055506bffffffffffffffffffffffff955060009450600093505b8651841015611145576060878581518110151561109557fe5b906010019060100201519060020a90046000925092508587858151811015156110ba57fe5b9060100190601002015116905080600a6000506000858582528160100152602001908152601001600020905060008282825054019250508190909055508085019450845082827fc6dcd8d437d8b3537583463d84a6ba9d7e3e013fa4e004da9b6dee1482038be5846040518082815260100191505060405180910390a25b838060010194505061107c565b84600b60008282825054019250508190909055505b5b5b5050505050505050565b6002600050806001015490543390911491901416156111f05760026000508060010154905460006000508282909180600101839055555050506000600060026000508282909180600101839055555050506000600050806001015490547fa701229f4b9ddf00aa1c7228d248e6320ee7c581d856ddfba036e73947cd0d1360405160405180910390a25b5b565b60006000508060010154905482565b60056000505481565b600c6000505481565b600060005080600101549054339091149190141615156112345760006000fd5b600660009054906101000a900460ff161515156112515760006000fd5b600082141515156112625760006000fd5b8160046000508190909055508060056000508190909055505b5b5b5050565b60046000505481565b600060005080600101549054339091149190141615156112aa5760006000fd5b818160026000508282909180600101839055555050505b5b5050565b60076000505481565b60006000600060005080600101549054339091149190141615156112f35760006000fd5b600660019054906101000a900460ff16151561130f5760006000fd5b6008600050806001015490546370a08231306000604051601001526040518363ffffffff166c0100000000000000000000000002815260040180838382528160100152602001925050506010604051808303816000888881813b15156113755760006000fd5b5af115156113835760006000fd5b50505050604051805190601001509150600c6000505482101515156113a85760006000fd5b600c600050548203905080600d600082828250540192505081909090555081600c6000508190909055505b5b5b5050565b600b6000505481565b600a600050602052818160005260105260306000209050600091509150505481565b600660019054906101000a900460ff1681565b61271081565b60026000508060010154905482565b60006004600050546005600050540161271061144d84610f8963ffffffff16565b600560005054010281151561145e57fe5b049050611466565b919050565b6000600050806001015490543390911491901416151561148b5760006000fd5b600660019054906101000a900460ff161580156114ad57506000600760005054145b15156114b95760006000fd5b60086000508060010154905463fbb001d68585856000604051601001526040518463ffffffff166c010000000000000000000000000281526004018084848252816010015260200182815260100193505050506010604051808303816000888881813b15156115285760006000fd5b5af115156115365760006000fd5b5050505060405180519060100150505b5b5b505050565b6000600050806001015490543390911491901416151561156d5760006000fd5b6001600f60006101000a81548160ff0219169083151502179055505b5b565b600061159d42610f8963ffffffff16565b90506115a4565b90565b600060005080600101549054339091149190141615156115c75760006000fd5b6001600660016101000a81548160ff0219169083151502179055505b5b565b600f60009054906101000a900460ff1681565b60106000505481565b600860005080600101549054825600a165627a7a723058207330977f143ddc0443cbdf8a24592705528c4e5f8db5866792270495cfa7cc830029";
    }

}