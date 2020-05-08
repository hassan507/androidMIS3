package pk.org.cerp.mischool.mischoolcompanion

class SMSPayload(val return_link: String, val messages: List<SMSItem>)

class SMSItem(val text: String, val number: String, var status: String? = "NOT_SENT")