package org.example.seedancegenarate.context;

import org.example.seedancegenarate.entity.AppUser;

public class UserContext {
    private static final ThreadLocal<AppUser> USER_HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUser(AppUser user) {
        USER_HOLDER.set(user);
    }

    public static AppUser getUser() {
        return USER_HOLDER.get();
    }

    public static Long getUserId() {
        AppUser user = getUser();
        return user == null ? null : user.getId();
    }

    public static Long requireUserId() {
        Long userId = getUserId();
        if (userId == null) {
            throw new RuntimeException("请先登录");
        }
        return userId;
    }

    public static boolean isAdmin() {
        AppUser user = getUser();
        return user != null && "ADMIN".equalsIgnoreCase(user.getRole());
    }

    public static void clear() {
        USER_HOLDER.remove();
    }
}
