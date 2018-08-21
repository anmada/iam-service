package db

databaseChangeLog(logicalFilePath: 'event_consumer_record.groovy') {
    changeSet(id: '2018-02-06-add-table-event_consumer_record', author: 'flyleft') {
        if(helper.dbType().isSupportSequence()){
            createSequence(sequenceName: 'event_consumer_record_s', startValue:"1")
        }
        createTable(tableName: "event_consumer_record") {
            column(name: 'uuid', type: 'VARCHAR(50)', autoIncrement: false, remarks: 'uuid') {
                constraints(primaryKey: true)
            }
            column(name: 'create_time', type: 'DATETIME', remarks: '创建时间')
        }
    }
}