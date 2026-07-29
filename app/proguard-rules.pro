# Keep JS bridge interface (called from JS by name)
-keepclassmembers class com.ai.agentcontroller.JsBridge {
    public *;
}
-keep class com.ai.agentcontroller.JsBridge { *; }

# Keep command models for JSON reflection
-keep class com.ai.agentcontroller.AgentCommand* { *; }
