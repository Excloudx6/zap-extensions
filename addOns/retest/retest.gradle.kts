description = "An add-on to retest for presence/absence of previously generated alerts."

zapAddOn {
    addOnName.set("Retest")

    manifest {
        author.set("ZAP Dev Team")
        url.set("https://www.zaproxy.org/docs/desktop/addons/retest/")
        dependencies {
            addOns {
                register("automation") {
                    version.set(">=0.20.0")
                }
            }
        }
    }

    apiClientGen {
        api.set("org.zaproxy.addon.retest.RetestAPI")
        messages.set(file("src/main/resources/org/zaproxy/addon/retest/resources/Messages.properties"))
    }
}

crowdin {
    configuration {
        val resourcesPath = "org/zaproxy/addon/${zapAddOn.addOnId.get()}/resources/"
        tokens.put("%messagesPath%", resourcesPath)
        tokens.put("%helpPath%", resourcesPath)
    }
}

dependencies {
    zapAddOn("automation")

    testImplementation(project(":testutils"))
}
