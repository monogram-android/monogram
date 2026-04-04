package org.monogram.data.gateway

import org.drinkless.tdlib.TdApi

class TdLibException(val error: TdApi.Error) : Exception(error.message)
