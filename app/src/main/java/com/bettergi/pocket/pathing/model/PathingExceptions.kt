package com.bettergi.pocket.pathing.model

/** 放弃当前路径段，不再重试。来源：BGI `HandledException`。 */
class HandledException(message: String) : Exception(message)

/** 正常结束当前路径段。来源：BGI `NormalEndException`。 */
class NormalEndException(message: String) : Exception(message)

/** 记录恢复点后重试，消耗一次重试。来源：BGI `RetryException`。 */
class RetryException(message: String) : Exception(message)

/** 记录恢复点后重试，不消耗次数。来源：BGI `RetryNoCountException`。 */
class RetryNoCountException(message: String) : Exception(message)
