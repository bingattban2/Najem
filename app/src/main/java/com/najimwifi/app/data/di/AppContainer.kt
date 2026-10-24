package com.najimwifi.app.data.di

import android.content.Context
import com.najimwifi.app.backup.LocalBackupManager
import com.najimwifi.app.data.local.NajimDatabase
import com.najimwifi.app.data.local.dao.ColumnDefinitionDao
import com.najimwifi.app.data.report.ReportComposer
import com.najimwifi.app.data.report.ReportQueries
import com.najimwifi.app.data.repository.AccountingRepositoryImpl
import com.najimwifi.app.data.repository.CatalogRepositoryImpl
import com.najimwifi.app.data.repository.LedgerReader
import com.najimwifi.app.data.repository.LedgerRepositoryImpl
import com.najimwifi.app.domain.contract.AccountingRepository
import com.najimwifi.app.domain.contract.CatalogRepository
import com.najimwifi.app.domain.service.SettlementService
import com.najimwifi.app.ui.home.HomeViewModel
import com.najimwifi.app.ui.management.ManagementViewModel
import com.najimwifi.app.ui.report.ReportViewModel

/** App-scoped dependency owner; construct once from Application context. */
class AppContainer(context: Context) {

    val database: NajimDatabase by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED
    ) {
        NajimDatabase.create(context.applicationContext)
    }

    /** Full offline backup and explicit restore; UI wiring follows in STEP 10.02. */
    val localBackupManager: LocalBackupManager by lazy {
        LocalBackupManager(context.applicationContext, database)
    }


    val accountingRepository: AccountingRepository by lazy {
        AccountingRepositoryImpl(database)
    }

    val ledgerReader: LedgerReader by lazy {
        LedgerRepositoryImpl(database)
    }

    val settlementService: SettlementService by lazy {
        SettlementService(accountingRepository)
    }

    val columnDefinitionDao: ColumnDefinitionDao by lazy {
        database.columnDefinitionDao()
    }

    val catalogRepository: CatalogRepository by lazy {
        CatalogRepositoryImpl(database)
    }

    val reportQueries: ReportQueries by lazy {
        ReportQueries(database)
    }

    val reportComposer: ReportComposer by lazy {
        ReportComposer(reportQueries)
    }

    /** Navigation must scope ViewModels to destination lifecycles. */
    fun newManagementViewModel(): ManagementViewModel =
        ManagementViewModel(catalogRepository)

    fun newHomeViewModel(ownerVerifier: suspend (String) -> String): HomeViewModel =
        HomeViewModel(
            database = database,
            catalog = catalogRepository,
            ownerVerifier = ownerVerifier
        )

    fun newReportViewModel(): ReportViewModel =
        ReportViewModel(reportComposer)
}