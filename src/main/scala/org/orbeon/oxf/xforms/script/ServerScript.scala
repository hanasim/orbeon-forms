/**
 *  Copyright (C) 2011 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.script

import org.mozilla.javascript.Context
import org.orbeon.oxf.xforms.{ScriptType, ShareableScript, StaticScript}

// NOTE: This is experimental and not in use as of 2016-01-19.
class ServerScript(
  prefixedId  : String,
  scriptType  : ScriptType,
  paramValues : List[String],
  shared      : ShareableScript
) extends StaticScript(
  prefixedId,
  scriptType,
  paramValues,
  shared
) {

//  private def getJavaScriptSource = scriptType match {
//    case "text/coffeescript" ⇒ CoffeeScriptCompiler.compile(shared.body, prefixedId, 1)// TODO: location information if available
//    case _ ⇒ shared.body
//  }

  private def getJavaScriptSource = ???

  lazy val compiledScript = synchronized { // synchronized might or might not be needed
    val cx = Context.enter()
    try {
      cx.compileString(getJavaScriptSource, prefixedId, 1, null)// TODO: location information if available
    } finally {
      Context.exit()
    }
  }
}